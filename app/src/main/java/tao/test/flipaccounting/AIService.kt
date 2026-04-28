package tao.test.flipaccounting

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tao.test.flipaccounting.data.local.AppDatabase
import tao.test.flipaccounting.data.local.entity.Asset
import tao.test.flipaccounting.data.local.entity.AiRule as DbAiRule
import tao.test.flipaccounting.data.local.entity.Bill
import com.google.gson.Gson
import com.google.gson.JsonObject
import android.util.Base64
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONArray
import org.json.JSONObject
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit
import tao.test.flipaccounting.chat.ai.AiIntentSlots
import tao.test.flipaccounting.chat.ai.AiBookkeepingMode
import tao.test.flipaccounting.chat.ai.AiIntentRouter
import tao.test.flipaccounting.chat.ai.AiIntentType
import tao.test.flipaccounting.chat.ai.AiRouteResult
import tao.test.flipaccounting.chat.ai.AiTimeRangeParser

// OCR 模式常量
const val OCR_MODE_LOCAL      = 0   // 本地 ML Kit OCR + 文本 AI
const val OCR_MODE_MULTIMODAL = 1   // 直接多模态 AI（发送图片）

object AIService {
    private const val LOCAL_RULE_APPLIED_FLAG = "_local_rule_applied"
    private const val LOCAL_RULE_CORRECTED_FLAG = "_local_rule_corrected"

    // 兼容旧版配置页引用（AiConfigActivity 等地方直接用 AIService.XXX 引用这些常量）
    const val DEFAULT_PROMPT                     = AIPrompts.SINGLE_BILL_PROMPT_DEFAULT
    const val MULTI_BILL_PROMPT                  = AIPrompts.MULTI_BILL_PROMPT_DEFAULT
    const val SINGLE_BILL_PROMPT_DEFAULT         = AIPrompts.SINGLE_BILL_PROMPT_DEFAULT
    const val MULTI_BILL_PROMPT_DEFAULT          = AIPrompts.MULTI_BILL_PROMPT_DEFAULT
    const val MODIFY_BILL_PROMPT_DEFAULT          = AIPrompts.MODIFY_BILL_PROMPT_DEFAULT
    val RULE_EXTRACT_PROMPT_DEFAULT              get() = tao.test.flipaccounting.logic.RuleDialogHelper.DEFAULT_RULE_PROMPT
    const val RECEIPT_BILL_PROMPT                = AIPrompts.RECEIPT_BILL_PROMPT
    const val RECEIPT_BILL_PROMPT_CN             = AIPrompts.RECEIPT_BILL_PROMPT_CN
    const val RECEIPT_BILL_PROMPT_FOREIGN        = AIPrompts.RECEIPT_BILL_PROMPT_FOREIGN
    const val RECEIPT_VISION_RETRY_PROMPT_DEFAULT= AIPrompts.RECEIPT_VISION_RETRY_PROMPT_DEFAULT
    const val SCREEN_ACCOUNTING_PROMPT_DEFAULT   = AIPrompts.SCREEN_ACCOUNTING_PROMPT_DEFAULT
    const val RECEIPT_OCR_REFINE_PROMPT_DEFAULT  = AIPrompts.RECEIPT_OCR_REFINE_PROMPT_DEFAULT
    const val MULTI_BILL_PROMPT_CONCISE          = AIPrompts.MULTI_BILL_PROMPT_CONCISE
    const val CHAT_ASSISTANT_PROMPT_DEFAULT      = AIPrompts.CHAT_ASSISTANT_PROMPT_DEFAULT
    const val INTENT_ROUTER_PROMPT_DEFAULT       = AIPrompts.INTENT_ROUTER_PROMPT_DEFAULT
    private const val MAX_ACCOUNTING_INPUT_CHARS = 12000
    private const val MAX_OCR_TEXT_CHARS = 14000
    private const val MAX_ASSISTANT_INPUT_CHARS = 4000
    private const val MAX_ASSISTANT_SUMMARY_CHARS = 2500
    private const val DEFAULT_CUSTOM_REPLY_STYLE_GUIDE =
        "回复风格：按用户自定义要求回复。请直接对用户说自然的人话，不要输出场景标签、英文状态词、JSON 或内部指令。"

    fun getDefaultSingleBillPrompt(ctx: Context): String =
        if (Prefs.isAssetFeatureEnabled(ctx)) {
            AIPrompts.SINGLE_BILL_PROMPT_DEFAULT
        } else {
            AIPromptsWithoutAccount.SINGLE_BILL_PROMPT_DEFAULT
        }

    fun getDefaultMultiBillPrompt(ctx: Context): String =
        if (Prefs.isAssetFeatureEnabled(ctx)) {
            AIPrompts.MULTI_BILL_PROMPT_DEFAULT
        } else {
            AIPromptsWithoutAccount.MULTI_BILL_PROMPT_DEFAULT
        }

    private fun getApi(ctx: Context): SiliconFlowApi {
        val baseUrl = normalizeBaseUrl(Prefs.getAiUrl(ctx))
        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(SiliconFlowApi::class.java)
    }

    private fun getSpeechApi(ctx: Context): SiliconFlowApi {
        val baseUrl = normalizeBaseUrl(Prefs.getAiUrl(ctx))
        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .build()
        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(SiliconFlowApi::class.java)
    }

    suspend fun speechToText(ctx: Context, audioFile: File): String? {
        val apiKey = Prefs.getAiKey(ctx)
        if (apiKey.isEmpty()) return null
        if (!audioFile.exists() || audioFile.length() <= 44L) return null

        val api = getSpeechApi(ctx)
        val mimeType = detectSpeechAudioMimeType(audioFile)
        val modelName = Prefs.getAiSpeechModel(ctx).trim()
        if (modelName.isEmpty()) return null
        val availableModels = Prefs.getAiModelsCache(ctx)
        if (availableModels.isEmpty()) {
            Logger.d(ctx, "AIService", "Cloud ASR skipped: no verified model cache for current provider")
            return null
        }
        if (!availableModels.contains(modelName)) {
            Logger.d(ctx, "AIService", "Cloud ASR skipped: model=$modelName not in current provider model cache")
            return null
        }

        return try {
            val requestFile = audioFile.asRequestBody(mimeType.toMediaTypeOrNull())
            val filePart = MultipartBody.Part.createFormData("file", audioFile.name, requestFile)
            val modelPart = MultipartBody.Part.createFormData("model", modelName)
            val response = api.transcribe("Bearer $apiKey", modelPart, filePart)
            val parsed = parseSpeechText(response)
            if (!parsed.isNullOrBlank()) {
                Logger.d(ctx, "AIService", "Cloud ASR success. model=$modelName, mime=$mimeType, textLen=${parsed.length}")
            } else {
                Logger.d(ctx, "AIService", "Cloud ASR empty response. model=$modelName")
            }
            parsed
        } catch (e: Exception) {
            Logger.d(ctx, "AIService", "Cloud ASR failed with model=$modelName, err=${detailedHttpError(e)}")
            null
        }
    }

    private fun parseSpeechText(response: AudioResponse): String? =
        listOf(response.text, response.result, response.transcript)
            .firstOrNull { !it.isNullOrBlank() }?.trim()

    suspend fun analyzeAccounting(
        ctx: Context,
        userInput: String,
        isMultiModeOverride: Boolean? = null,
        onProgress: ((String) -> Unit)? = null
    ): JSONObject? {
        val safeUserInput = shortenForModel(userInput, MAX_ACCOUNTING_INPUT_CHARS)
        Logger.d(ctx, "AIService", "Analyzing: $safeUserInput")
        val apiKey = Prefs.getAiKey(ctx)
        val isMultiMode = isMultiModeOverride ?: Prefs.isMultiBillEnabled(ctx)
        val model = if (isMultiMode) Prefs.getAiMultiModel(ctx) else Prefs.getAiSingleModel(ctx)
        if (apiKey.isEmpty()) throw IllegalArgumentException("请先在设置中配置 API Key")
        val promptContext = buildAccountingPromptContext(ctx)

        val promptRules = loadActivePromptRules(ctx)
        val matchedPromptRules = if (Prefs.isAiPromptCorrectionEnabled(ctx)) {
            findMatchedPromptRules(safeUserInput, promptRules)
        } else {
            emptyList()
        }
        val localPrefill = if (!isMultiMode) {
            resolveLocalRulePrefill(matchedPromptRules)
        } else {
            null
        }

        val systemPrompt = buildAccountingSystemPrompt(
            ctx = ctx,
            promptContext = promptContext,
            isMultiMode = isMultiMode,
            matchedPromptRules = matchedPromptRules,
            localPrefill = localPrefill
        )

        return try {
            val requestJson = buildTextChatRequest(
                model = model,
                temperature = 0.3,
                systemPrompt = systemPrompt,
                userText = safeUserInput
            )
            if (!isMultiMode && matchedPromptRules.isNotEmpty()) {
                onProgress?.invoke("本地规则匹配中...")
            }
            onProgress?.invoke("智能分析中...")
            val response = getApi(ctx).chatRaw("Bearer $apiKey", requestJson)
            val content = response.choices.first().message.content
            Logger.d(ctx, "AIService", "AI Response: $content")

            // 直接解析，不再对原始 JSON 字符串做规则覆盖
            val result = parseAnalyzeResult(content, isMultiMode)

            result?.let { root ->
                if (shouldTreatAsNoBillChatter(safeUserInput, root)) {
                    return JSONObject().apply {
                        put("no_bill", true)
                        put("reply", "这句更像是在聊天，不像记账内容，我就先不帮你生成账单啦。")
                    }
                }
                // ── 第1步：小票强制支出 ──
                enforceExpenseForReceiptSummaries(root, safeUserInput)
                if (!isMultiMode && localPrefill != null) {
                    val before = summarizeLocalRuleSensitiveFields(root)
                    val applyResult = applyLocalPrefillToResult(root, localPrefill)
                    if (applyResult.applied) {
                        root.put(LOCAL_RULE_APPLIED_FLAG, true)
                        if (applyResult.corrected) {
                            root.put(LOCAL_RULE_CORRECTED_FLAG, true)
                        }
                        Logger.d(
                            ctx,
                            "AIService",
                            "Local rule override applied; corrected=${applyResult.corrected}; correctedFields=${applyResult.correctedFields.joinToString(",")}; changed=${applyResult.changedFields.joinToString(",")}; before=$before; after=${summarizeLocalRuleSensitiveFields(root)}"
                        )
                    }
                }

                if (isMultiMode && root.has("bills") && !Prefs.isMultiBillFastMode(ctx)) {
                    refineMultiBillCategories(
                        ctx = ctx,
                        root = root,
                        expenseCats = promptContext.expenseCats,
                        incomeCats = promptContext.incomeCats,

                        allPromptRules = promptRules,
                        onProgress = onProgress
                    )
                }
                normalizeAccountingResult(
                    root = root,
                    expenseCats = promptContext.expenseCats,
                    incomeCats = promptContext.incomeCats,
                    assetNames = promptContext.assetNames,
                    assetFeatureEnabled = promptContext.assetFeatureEnabled
                )
            }
            result
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Logger.d(ctx, "AIService", "AI Request Failed: ${detailedHttpError(e)}")
            throw e
        }
    }

    suspend fun analyzeAccountingByAudio(
        ctx: Context,
        audioFile: File,
        isMultiModeOverride: Boolean? = null,
        onProgress: ((String) -> Unit)? = null
    ): JSONObject? {
        require(audioFile.exists() && audioFile.length() > 44L) { "语音文件无效" }

        val apiKey = Prefs.getAiKey(ctx)
        val isMultiMode = isMultiModeOverride ?: Prefs.isMultiBillEnabled(ctx)
        val model = if (isMultiMode) Prefs.getAiMultiModel(ctx) else Prefs.getAiSingleModel(ctx)
        if (apiKey.isEmpty()) throw IllegalArgumentException("请先在设置中配置 API Key")
        val promptContext = buildAccountingPromptContext(ctx)
        val promptRules = loadActivePromptRules(ctx)

        val systemPrompt = buildAudioAccountingSystemPrompt(
            ctx = ctx,
            promptContext = promptContext,
            isMultiMode = isMultiMode
        )

        val audioBase64 = withContext(Dispatchers.IO) {
            Base64.encodeToString(audioFile.readBytes(), Base64.NO_WRAP)
        }
        val audioFormat = audioFile.extension.lowercase(Locale.ROOT).ifBlank { "wav" }

        return try {
            val requestJson = buildAudioChatRequest(
                model = model,
                temperature = 0.3,
                systemPrompt = systemPrompt,
                leadText = "这是一段用户口述记账语音。请严格按系统提示词要求提取账单 JSON。",
                audioBase64 = audioBase64,
                audioFormat = audioFormat
            )
            onProgress?.invoke("智能分析中...")
            val response = getApi(ctx).chatRaw("Bearer $apiKey", requestJson)
            val content = response.choices.first().message.content
            Logger.d(ctx, "AIService", "AI Audio Response: $content")
            val result = parseAnalyzeResult(content, isMultiMode)

            result?.let { root ->
                if (shouldTreatAsNoBillChatter("[语音输入]", root)) {
                    return JSONObject().apply {
                        put("no_bill", true)
                        put("reply", "这段语音更像是在聊天，不像需要落账的内容，我先按聊天回复你。")
                    }
                }
                if (isMultiMode && root.has("bills") && !Prefs.isMultiBillFastMode(ctx)) {
                    refineMultiBillCategories(
                        ctx = ctx,
                        root = root,
                        expenseCats = promptContext.expenseCats,
                        incomeCats = promptContext.incomeCats,

                        allPromptRules = promptRules,
                        onProgress = onProgress
                    )
                }
                normalizeAccountingResult(
                    root = root,
                    expenseCats = promptContext.expenseCats,
                    incomeCats = promptContext.incomeCats,
                    assetNames = promptContext.assetNames,
                    assetFeatureEnabled = promptContext.assetFeatureEnabled
                )
            }
            result
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Logger.d(ctx, "AIService", "AI Audio Request Failed: ${detailedHttpError(e)}")
            throw e
        }
    }

    suspend fun analyzeReceiptByImage(ctx: Context, imageBase64: String, mimeType: String = "image/jpeg"): String {
        Logger.d(ctx, "AIService", "analyzeReceiptByImage: multimodal mode")
        val apiKey = Prefs.getAiKey(ctx)
        if (apiKey.isEmpty()) throw IllegalArgumentException("请先在设置中配置 API Key")

        val model = Prefs.getAiReceiptVisionModel(ctx).ifBlank { Prefs.getAiReceiptModel(ctx) }
        val systemPrompt = Prefs.getReceiptVisionPrompt(ctx).ifBlank { AIPrompts.RECEIPT_VISION_RETRY_PROMPT_DEFAULT }
        val dataUrl = "data:$mimeType;base64,$imageBase64"

        val requestJson = buildVisionChatRequest(
            model = model,
            temperature = 0.3,
            systemPrompt = systemPrompt,
            dataUrl = dataUrl,
            userText = "请分析这张小票订单图片，转为自然语言清单。"
        )

        return try {
            val response = getApi(ctx).chatRaw("Bearer $apiKey", requestJson)
            val content = response.choices.first().message.content
            Logger.d(ctx, "AIService", "Receipt multimodal response: $content")
            content
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Logger.d(ctx, "AIService", "analyzeReceiptByImage failed: ${detailedHttpError(e)}")
            throw e
        }
    }

    suspend fun probeVisionInputSupport(ctx: Context, modelName: String? = null): Boolean {
        val apiKey = Prefs.getAiKey(ctx)
        if (apiKey.isBlank()) return false
        val model = modelName?.takeIf { it.isNotBlank() } ?: Prefs.getAiScreenModel(ctx)
        if (model.isBlank()) return false
        return runCatching {
            Logger.d(ctx, "AIService", "Probing vision input support. model=$model")
            val requestJson = buildVisionChatRequest(
                model = model,
                temperature = 0.1,
                dataUrl = "data:image/png;base64,${buildProbeImageBase64(ctx)}",
                userText = "Reply with OK only."
            )
            val response = getApi(ctx).chatRaw("Bearer $apiKey", requestJson)
            Logger.d(ctx, "AIService", "Vision input support probe succeeded. model=$model")
            response.choices.firstOrNull()?.message?.content != null
        }.getOrElse {
            Logger.d(ctx, "AIService", "Vision input support probe failed. model=$model, err=${detailedHttpError(it as? Exception ?: Exception(it.message))}")
            false
        }
    }

    suspend fun analyzeScreenAccountingByImage(
        ctx: Context,
        imageBase64: String,
        mimeType: String = "image/jpeg",
        isMultiModeOverride: Boolean? = null
    ): JSONObject? {
        Logger.d(ctx, "AIService", "analyzeScreenAccountingByImage: multimodal accounting mode")
        val apiKey = Prefs.getAiKey(ctx)
        if (apiKey.isBlank()) throw IllegalArgumentException("请先在设置中配置 API Key")

        val isMultiMode = isMultiModeOverride ?: Prefs.isMultiBillEnabled(ctx)
        val model = Prefs.getAiScreenModel(ctx).trim()
        if (model.isBlank()) throw IllegalArgumentException("请先在智能配置中选择屏幕识别模型")

        val promptContext = buildAccountingPromptContext(ctx)
        val systemPrompt = buildScreenAccountingSystemPrompt(
            ctx = ctx,
            promptContext = promptContext,
            isMultiMode = isMultiMode
        )

        val dataUrl = "data:$mimeType;base64,$imageBase64"
        val requestJson = buildVisionChatRequest(
            model = model,
            temperature = 0.2,
            systemPrompt = systemPrompt,
            dataUrl = dataUrl,
            userText = if (isMultiMode) {
                "这是一张手机屏幕截图。请忽略界面装饰，只提取真实交易信息，并直接返回 {\"bills\":[...]}。"
            } else {
                "这是一张手机屏幕截图。请忽略界面装饰，只提取最明确的一条真实交易，并直接返回单个 JSON 对象。"
            }
        )

        return try {
            val response = getApi(ctx).chatRaw("Bearer $apiKey", requestJson)
            val content = response.choices.first().message.content
            Logger.d(ctx, "AIService", "Screen accounting multimodal response: $content")
            val result = parseAnalyzeResult(content, isMultiMode)

            result?.let { root ->
                normalizeAccountingResult(
                    root = root,
                    expenseCats = promptContext.expenseCats,
                    incomeCats = promptContext.incomeCats,
                    assetNames = promptContext.assetNames,
                    assetFeatureEnabled = promptContext.assetFeatureEnabled
                )
            }
            result
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Logger.d(ctx, "AIService", "analyzeScreenAccountingByImage failed: ${detailedHttpError(e)}")
            throw e
        }
    }

    suspend fun analyzeReceiptByOcrText(ctx: Context, ocrText: String): String {
        Logger.d(ctx, "AIService", "analyzeReceiptByOcrText, text length=${ocrText.length}")
        val apiKey = Prefs.getAiKey(ctx)
        if (apiKey.isEmpty()) throw IllegalArgumentException("请先在设置中配置 API Key")

        val model = Prefs.getAiReceiptModel(ctx)
        val systemPrompt = AIReceiptHelper.buildReceiptSystemPrompt(ctx, ocrText)
        val cleanedOcrText = shortenForModel(
            AIReceiptHelper.preprocessOcrTextForReceipt(ocrText),
            MAX_OCR_TEXT_CHARS
        )
        val knownPatternSummary = AIReceiptHelper.buildReceiptSummaryDirectlyFromOcr(ocrText)

        if (!knownPatternSummary.isNullOrBlank() && Prefs.isReceiptOcrRefineEnabled(ctx)) {
            try {
                val refined = refineReceiptSummaryWithTextModel(ctx, knownPatternSummary, ocrText)
                Logger.d(ctx, "AIService", "Using OCR pattern candidates -> LLM refine summary")
                return refined
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Logger.d(ctx, "AIService", "OCR pattern->LLM refine failed, continue: ${detailedHttpError(e)}")
            }
        }

        return try {
            fun buildRequestJson(forceJsonResponse: Boolean): com.google.gson.JsonObject {
                val localHintBlock = if (knownPatternSummary.isNullOrBlank()) ""
                    else "参考候选商品（来自本地OCR结构提取，仅供校验）：\n$knownPatternSummary\n\n"
                return buildTextChatRequest(
                    model = model,
                    temperature = 0.1,
                    systemPrompt = systemPrompt,
                    userText = "请执行「小票结构化提取」并严格只返回一个 JSON 对象。\nJSON 结构：\n{\n  \"currency\": \"PLN\",\n  \"items\": [\n    {\"name\":\"商品名\",\"price\":5.89,\"currency\":\"PLN\"}\n  ]\n}\n\n约束：\n1. 只保留同时具备「商品名 + 实付金额」的商品行。\n2. 不要输出总计行、税率行、NIP、日期时间、店铺编号、Discount 等非商品行。\n3. OCR 重复行只保留一条，不能重复计数。\n4. 价格必须来自 OCR 原文，禁止臆造。\n5. 若存在\"数量 x 单价 金额\"结构，优先使用该结构确定数量和最终实付金额。\n6. 如果遇到 Opust/Discount，应使用折后金额（净额）作为该商品金额。\n\n${localHintBlock}以下是 OCR 文本：\n$cleanedOcrText",
                    jsonObjectResponse = forceJsonResponse
                )
            }

            val response = try {
                getApi(ctx).chatRaw("Bearer $apiKey", buildRequestJson(forceJsonResponse = true))
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Logger.d(ctx, "AIService", "Receipt OCR json_object mode failed, retry: ${detailedHttpError(e)}")
                getApi(ctx).chatRaw("Bearer $apiKey", buildRequestJson(forceJsonResponse = false))
            }
            val content = response.choices.first().message.content
            Logger.d(ctx, "AIService", "Receipt OCR structured response: $content")
            AIReceiptHelper.buildReceiptSummaryFromStructured(content, ocrText)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            val fallback = knownPatternSummary ?: AIReceiptHelper.buildReceiptSummaryHeuristicFallback(ocrText)
            if (!fallback.isNullOrBlank()) {
                Logger.d(ctx, "AIService", "analyzeReceiptByOcrText failed, using fallback: ${detailedHttpError(e)}")
                fallback
            } else {
                Logger.d(ctx, "AIService", "analyzeReceiptByOcrText failed: ${detailedHttpError(e)}")
                throw e
            }
        }
    }

    private suspend fun refineReceiptSummaryWithTextModel(ctx: Context, localSummary: String, originalOcrText: String): String {
        val apiKey = Prefs.getAiKey(ctx)
        if (apiKey.isEmpty()) return localSummary

        val model = Prefs.getAiReceiptOcrRefineModel(ctx)
        val systemPrompt = Prefs.getReceiptOcrRefinePrompt(ctx).ifBlank { AIPrompts.RECEIPT_OCR_REFINE_PROMPT_DEFAULT }
        val cleanedOcrText = shortenForModel(
            AIReceiptHelper.preprocessOcrTextForReceipt(originalOcrText),
            MAX_OCR_TEXT_CHARS
        )

        val requestJson = buildTextChatRequest(
            model = model,
            temperature = 0.1,
            systemPrompt = systemPrompt,
            userText = "本地OCR已提取清单（金额可信）：\n$localSummary\n\n原始OCR（仅用于校对，不允许引入新金额）：\n$cleanedOcrText"
        )

        val response = getApi(ctx).chatRaw("Bearer $apiKey", requestJson)
        val content = response.choices.first().message.content.trim()
        return AIReceiptHelper.sanitizeReceiptSummaryText(content, originalOcrText)
            ?: content.ifBlank { localSummary }
    }

    suspend fun fetchModels(ctx: Context, apiKey: String): List<String> =
        fetchModelsWithDetails(Prefs.getAiUrl(ctx), apiKey)

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

    suspend fun simpleChat(ctx: Context, prompt: String): String {
        val apiKey = Prefs.getAiKey(ctx)
        val model = Prefs.getAiRuleModel(ctx)
        if (apiKey.isEmpty()) throw IllegalArgumentException("请先在设置中配置 API Key")
        val request = ChatRequest(
            model = model,
            messages = listOf(MessageUnion.Text(Message("user", prompt))),
            response_format = null
        )
        val response = getApi(ctx).chatRaw("Bearer $apiKey", buildRawRequest(request))
        return response.choices.first().message.content
    }

    suspend fun routeIntentWithModel(
        ctx: Context,
        userInput: String,
        chatHistoryContext: String = ""
    ): AiRouteResult? {
        val apiKey = Prefs.getAiKey(ctx)
        val model = Prefs.getAiRouterModel(ctx).ifBlank { return null }
        if (apiKey.isEmpty()) throw IllegalArgumentException("请先在设置中配置 API Key")

        val safeUserInput = shortenForModel(userInput, 1200, preserveTail = false)
        val contextBlock = if (chatHistoryContext.isNotBlank())
            "\n\n【近期对话记录（仅供参考，帮助你判断用户意图）】\n${shortenForModel(chatHistoryContext, 600)}\n\n【用户当前输入】"
        else ""
        val requestJson = buildTextChatRequest(
            model = model,
            temperature = 0.0,
            systemPrompt = INTENT_ROUTER_PROMPT_DEFAULT.trim() + contextBlock,
            userText = safeUserInput,
            jsonObjectResponse = true
        )

        val response = getApi(ctx).chatRaw("Bearer $apiKey", requestJson)
        val content = response.choices.firstOrNull()?.message?.content.orEmpty()
        return parseRouterResult(content, userInput)
    }

    suspend fun generateAccountingModifyReply(
        ctx: Context,
        userInput: String,
        oldBillJson: String
    ): String {
        val apiKey = Prefs.getAiKey(ctx)
        if (apiKey.isEmpty()) throw IllegalArgumentException("请先在设置中配置 API Key")
        val model = Prefs.getAiSingleModel(ctx)
        val safeInput = shortenForModel(userInput, MAX_ASSISTANT_INPUT_CHARS)
        val requestJson = buildTextChatRequest(
            model = model,
            temperature = 0.0,
            systemPrompt = AIPrompts.MODIFY_BILL_PROMPT_DEFAULT.trim(),
            userText = "上一批次账单列表（JSON数组）：\n$oldBillJson\n\n用户修改指令：$safeInput"
        )
        val response = getApi(ctx).chatRaw("Bearer $apiKey", requestJson)
        return response.choices.firstOrNull()?.message?.content?.trim().orEmpty()
    }

    private fun parseRouterResult(content: String, userInput: String): AiRouteResult? {
        val root = runCatching { JSONObject(cleanJsonString(content)) }.getOrNull() ?: return null
        val type = runCatching {
            AiIntentType.valueOf(root.optString("intent_type", "UNKNOWN").trim().uppercase(Locale.ROOT))
        }.getOrDefault(AiIntentType.UNKNOWN)
        val confidence = root.optDouble("confidence", 0.0).coerceIn(0.0, 1.0)
        val slotsObj = root.optJSONObject("slots")
        val localRoute = AiIntentRouter.route(userInput)
        val bookkeepingMode = root.optNullableString("bookkeeping_mode")
            ?.let { raw ->
                runCatching { AiBookkeepingMode.valueOf(raw.trim().uppercase(Locale.ROOT)) }.getOrNull()
            }
            ?: localRoute.bookkeepingMode
        val timePhrase = slotsObj?.optNullableString("timeRange")
        val timeRange = timePhrase
            ?.let { AiTimeRangeParser.parse(it) }
            ?: localRoute.slots.timeRange
            ?: AiTimeRangeParser.parse(userInput)
        val accountSlot: String? = slotsObj?.optNullableString("account") ?: localRoute.slots.account
        val categorySlot: String? = slotsObj?.optNullableString("category") ?: localRoute.slots.category
        val keywordSlot: String? = slotsObj?.optNullableString("keyword") ?: localRoute.slots.keyword
        val slots = AiIntentSlots(
            timeRange = timeRange,
            account = accountSlot,
            category = categorySlot,
            amount = slotsObj?.optNullableDouble("amount") ?: localRoute.slots.amount,
            keyword = keywordSlot
        )
        return AiRouteResult(type, confidence, slots, bookkeepingMode)
    }

    suspend fun generateAccountingAssistantReply(
        ctx: Context,
        userInput: String,
        billSummary: String = "",
        extractorReplyHint: String = "",
        chatHistoryContext: String = ""
    ): String {
        val apiKey = Prefs.getAiKey(ctx)
        if (apiKey.isEmpty()) throw IllegalArgumentException("请先在设置中配置 API Key")

        val model = Prefs.getAiChatModel(ctx).ifBlank { Prefs.getAiSingleModel(ctx) }
        val safeUserInput = shortenForModel(userInput, MAX_ASSISTANT_INPUT_CHARS)
        val safeBillSummary = shortenForModel(billSummary, MAX_ASSISTANT_SUMMARY_CHARS)
        val safeReplyHint = shortenForModel(extractorReplyHint, MAX_ASSISTANT_INPUT_CHARS, preserveTail = false)
        val styleInstruction = buildAssistantStyleInstruction(ctx, DEFAULT_CUSTOM_REPLY_STYLE_GUIDE)
        val userPrompt = buildAccountingAssistantUserPrompt(
            userInput = safeUserInput,
            billSummary = safeBillSummary,
            extractorReplyHint = safeReplyHint,
            styleInstruction = styleInstruction,
            chatHistoryContext = chatHistoryContext
        )

        val requestJson = buildTextChatRequest(
            model = model,
            temperature = 0.7,
            systemPrompt = CHAT_ASSISTANT_PROMPT_DEFAULT,
            userText = userPrompt
        )
        val response = getApi(ctx).chatRaw("Bearer $apiKey", requestJson)
        return response.choices.firstOrNull()?.message?.content?.trim().orEmpty()
    }

    suspend fun generateGeneralChatReply(ctx: Context, userInput: String, chatHistoryContext: String = ""): String {
        val apiKey = Prefs.getAiKey(ctx)
        if (apiKey.isEmpty()) throw IllegalArgumentException("请先在设置中配置 API Key")

        val model = Prefs.getAiChatModel(ctx).ifBlank { Prefs.getAiSingleModel(ctx) }
        val safeUserInput = shortenForModel(userInput, MAX_ASSISTANT_INPUT_CHARS)
        val styleInstruction = buildAssistantStyleInstruction(ctx, DEFAULT_CUSTOM_REPLY_STYLE_GUIDE)
        val contextBlock = if (chatHistoryContext.isNotBlank()) "【相关历史对话记录】\n$chatHistoryContext\n\n【用户最新输入】\n" else ""
        val requestJson = buildTextChatRequest(
            model = model,
            temperature = 0.7,
            systemPrompt = "你是 FlipAccounting 的 AI 对话助手。用户在记账应用里和你聊天；如果不是记账或查询任务，请自然、简洁地回复，不要输出 JSON 或内部标签。",
            userText = "$styleInstruction\n${contextBlock}用户：$safeUserInput"
        )
        val response = getApi(ctx).chatRaw("Bearer $apiKey", requestJson)
        return response.choices.firstOrNull()?.message?.content?.trim().orEmpty()
    }

    suspend fun streamAccountingAssistantReply(
        ctx: Context,
        userInput: String,
        billSummary: String = "",
        extractorReplyHint: String = "",
        chatHistoryContext: String = "",
        onDelta: (String) -> Unit
    ): Boolean {
        val apiKey = Prefs.getAiKey(ctx)
        if (apiKey.isEmpty()) throw IllegalArgumentException("请先在设置中配置 API Key")

        val model = Prefs.getAiChatModel(ctx).ifBlank { Prefs.getAiSingleModel(ctx) }
        val safeUserInput = shortenForModel(userInput, MAX_ASSISTANT_INPUT_CHARS)
        val safeBillSummary = shortenForModel(billSummary, MAX_ASSISTANT_SUMMARY_CHARS)
        val safeReplyHint = shortenForModel(extractorReplyHint, MAX_ASSISTANT_INPUT_CHARS, preserveTail = false)
        val styleInstruction = buildAssistantStyleInstruction(ctx, DEFAULT_CUSTOM_REPLY_STYLE_GUIDE)
        val userPrompt = buildAccountingAssistantUserPrompt(
            userInput = safeUserInput,
            billSummary = safeBillSummary,
            extractorReplyHint = safeReplyHint,
            styleInstruction = styleInstruction,
            chatHistoryContext = chatHistoryContext
        )

        val requestJson = buildTextChatRequest(
            model = model,
            temperature = 0.7,
            systemPrompt = CHAT_ASSISTANT_PROMPT_DEFAULT,
            userText = userPrompt,
            stream = true
        )

        return try {
            val body = getApi(ctx).chatStreamRaw("Bearer $apiKey", requestJson)
            body.use { responseBody ->
                val source = responseBody.source()
                while (!source.exhausted()) {
                    val line = source.readUtf8Line() ?: break
                    if (!line.startsWith("data:")) continue
                    val payload = line.removePrefix("data:").trim()
                    if (payload == "[DONE]") break
                    runCatching {
                        val root = JSONObject(payload)
                        val delta = root.optJSONArray("choices")
                            ?.optJSONObject(0)
                            ?.optJSONObject("delta")
                            ?.optString("content")
                            .orEmpty()
                        if (delta.isNotEmpty()) onDelta(delta)
                    }
                }
            }
            true
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            false
        }
    }

    suspend fun probeDirectAudioInputSupport(ctx: Context, modelName: String? = null): Boolean {
        val apiKey = Prefs.getAiKey(ctx)
        if (apiKey.isBlank()) return false
        val model = modelName?.takeIf { it.isNotBlank() }
            ?: Prefs.getAiChatModel(ctx).ifBlank { Prefs.getAiSingleModel(ctx) }
        return runCatching {
            val requestJson = buildAudioChatRequest(
                model = model,
                temperature = 0.1,
                systemPrompt = null,
                leadText = "Reply with OK only.",
                audioBase64 = buildProbeAudioBase64(),
                audioFormat = "wav"
            )
            val response = getApi(ctx).chatRaw("Bearer $apiKey", requestJson)
            response.choices.firstOrNull()?.message?.content != null
        }.getOrElse { false }
    }

    /**
     * 本地规则强覆盖（最终裁决）。
     * 直接在已解析的 JSONObject 上原地修改，保证规则的 type / category_name /
     * asset_name / to_asset_name 不会再被后续步骤覆盖。
     */
    private fun applyLocalRuleOverrideOnResult(root: JSONObject, userInput: String, allRules: List<tao.test.flipaccounting.data.local.entity.AiRule>) {
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
            android.util.Log.d("AIService", "Local rule override applied: $root")
        } catch (e: Exception) {
            android.util.Log.d("AIService", "applyLocalRuleOverrideOnResult error: ${e.message}")
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
        // 无可记账信息：AI 返回 {"no_bill":true,"reply":"..."}
        if (json.optBoolean("no_bill", false)) {
            return json // 透传给 ChatActivity 识别
        }
        return if (isMultiMode) {
            when {
                json.has("bills")  -> json
                json.has("amount") -> JSONObject().apply { put("bills", JSONArray().put(json)) }
                else -> throw IllegalArgumentException("多账单模式下 AI 返回的数据缺少关键字段 'bills' 或 'amount'")
            }
        } else {
            if (json.has("bills")) {
                val bills = json.getJSONArray("bills")
                if (bills.length() > 0) bills.getJSONObject(0) else null
            } else json
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
                weakFinancialSignalHits >= 2
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

    private suspend fun refineMultiBillCategories(
        ctx: Context,
        root: JSONObject,
        expenseCats: List<String>,
        incomeCats: List<String>,
        allPromptRules: List<DbAiRule>,
        onProgress: ((String) -> Unit)? = null
    ) {
        val bills = root.optJSONArray("bills") ?: return
        val unresolvedIndexes = mutableListOf<Int>()
        val unresolvedRemarks = mutableListOf<String>()
        val unresolvedCandidates = mutableListOf<List<String>>()
        val progressLines = MutableList(bills.length()) { "" }
        onProgress?.invoke("本地规则匹配中...")
        for (i in 0 until bills.length()) {
            val bill = bills.optJSONObject(i) ?: continue
            val rawType = bill.optInt("type", 0)
            val type = normalizeBillType(rawType)
            bill.put("type", type)
            if (type == 2) {
                if (rawType == 3 || bill.optInt("subType", 0) == Bill.SUBTYPE_REPAYMENT || bill.optString("category_name") == "还款") {
                    bill.put("subType", Bill.SUBTYPE_REPAYMENT)
                    bill.put("category_name", "还款")
                } else if (bill.optString("category_name").isBlank()) {
                    bill.put("category_name", "转账")
                }
                val shownCategory = bill.optString("category_name", "").ifBlank { "待定分类" }
                val transferRemark = bill.optString("remarks", "").trim()
                    .ifEmpty { bill.optString("remark", "").trim() }
                progressLines[i] = "${i + 1}. ${transferRemark.take(18)} -> $shownCategory"
                onProgress?.invoke(
                    buildString {
                        append("本地规则匹配中 ${i + 1}/${bills.length()}...\n")
                        append(progressLines.filter { it.isNotBlank() }.joinToString("\n"))
                    }
                )
                continue
            }

            val remark = bill.optString("remarks", "").trim()
                .ifEmpty { bill.optString("remark", "").trim() }
            if (remark.isBlank()) continue

            val candidates = if (type == 1) incomeCats else expenseCats
            val matchedRules = if (Prefs.isAiPromptCorrectionEnabled(ctx)) {
                findMatchedPromptRules(remark, allPromptRules)
            } else {
                emptyList()
            }

            val localCategory = resolveCategoryByLocalRules(matchedRules, candidates)
            if (!localCategory.isNullOrBlank()) {
                bill.put("category_name", localCategory)
                Logger.d(
                    ctx,
                    "AIService",
                    "Multi refine precheck idx=${i + 1}/${bills.length()}, remark=${remark.take(24)}, resolvedBy=local_rule, category=$localCategory"
                )
            } else {
                bill.put("category_name", "")
                unresolvedIndexes += i
                unresolvedRemarks += remark
                unresolvedCandidates += candidates
                Logger.d(
                    ctx,
                    "AIService",
                    "Multi refine precheck idx=${i + 1}/${bills.length()}, remark=${remark.take(24)}, resolvedBy=pending_llm, candidateCount=${candidates.size}"
                )
            }

            val shownCategory = bill.optString("category_name", "").ifBlank { "待定分类" }
            progressLines[i] = "${i + 1}. ${remark.take(18)} -> $shownCategory"
            onProgress?.invoke(
                buildString {
                    append("本地规则匹配中 ${i + 1}/${bills.length()}...\n")
                    append(progressLines.filter { it.isNotBlank() }.joinToString("\n"))
                }
            )
        }

        if (unresolvedIndexes.isNotEmpty()) {
            unresolvedIndexes.forEachIndexed { unresolvedPos, billIndex ->
                val bill = bills.optJSONObject(billIndex) ?: return@forEachIndexed
                val remark = unresolvedRemarks.getOrElse(unresolvedPos) { "" }
                val candidates = unresolvedCandidates.getOrElse(unresolvedPos) { emptyList() }
                val matchedRules = if (Prefs.isAiPromptCorrectionEnabled(ctx)) {
                    findMatchedPromptRules(remark, allPromptRules)
                } else {
                    emptyList()
                }
                onProgress?.invoke(
                    buildString {
                        append("智能分类中，第 ${unresolvedPos + 1}/${unresolvedIndexes.size} 条...\n")
                        append(progressLines.filter { it.isNotBlank() }.joinToString("\n"))
                    }
                )
                val refined = runCatching {
                    classifyBillCategoryByRemark(
                        ctx = ctx,
                        remark = remark,
                        type = bill.optInt("type", 0),
                        candidates = candidates,
                        matchedRules = matchedRules
                    )
                }.getOrNull()
                bill.put("category_name", refined ?: "")
                Logger.d(
                    ctx,
                    "AIService",
                    "Multi refine llm idx=${billIndex + 1}/${bills.length()}, remark=${remark.take(24)}, final=${(refined ?: "").ifBlank { "<empty>" }}"
                )
                progressLines[billIndex] = "${billIndex + 1}. ${remark.take(18)} -> ${bill.optString("category_name", "").ifBlank { "待定分类" }}"
                onProgress?.invoke(
                    buildString {
                        append("智能分类中，已完成 ${unresolvedPos + 1}/${unresolvedIndexes.size} 条...\n")
                        append(progressLines.filter { it.isNotBlank() }.joinToString("\n"))
                    }
                )
            }
        }
    }

    private suspend fun classifyBillCategoryByRemark(
        ctx: Context,
        remark: String,
        type: Int,
        candidates: List<String>,
        matchedRules: List<DbAiRule>
    ): String? {
        val apiKey = Prefs.getAiKey(ctx)
        if (apiKey.isEmpty()) return null

        val model = Prefs.getAiSingleModel(ctx).ifBlank { Prefs.getAiMultiModel(ctx) }
        Logger.d(
            ctx,
            "AIService",
            "Category refine request: model=$model, type=${if (type == 1) "income" else "expense"}, remark=${remark.take(32)}, candidateCount=${candidates.size}, matchedRuleCount=${matchedRules.size}"
        )
        val correctionBlock = if (matchedRules.isNotEmpty()) buildPromptCorrectionBlock(matchedRules) else ""
        val systemPrompt = AIPrompts.buildCategoryRefineSystemPrompt(
            type = type,
            candidatesJson = Gson().toJson(candidates),
            hierarchyHint = buildCategoryHierarchyHint(candidates),
            correctionBlock = correctionBlock
        )
        val userPrompt = AIPrompts.buildCategoryRefineUserPrompt(remark)

        val requestJson = buildTextChatRequest(
            model = model,
            temperature = 0.1,
            systemPrompt = systemPrompt,
            userText = userPrompt
        )

        val response = getApi(ctx).chatRaw("Bearer $apiKey", requestJson)
        val content = response.choices.firstOrNull()?.message?.content.orEmpty()
        val parsed = runCatching { parseAnalyzeResult(content, isMultiMode = false) }
            .getOrElse {
                Logger.d(
                    ctx,
                    "AIService",
                    "Category refine parse failed: remark=${remark.take(32)}, response=${content.take(160)}, err=${it.message}"
                )
                return null
            } ?: return null
        val rawCate = parsed.optString("category_name", "")
        val normalized = rawCate.replace(" > ", "/::/").replace(" - ", "/::/").replace(" / ", "/::/").trim()
        val strictMatch = findBestMatch(normalized, candidates)
        val semanticMatch = if (strictMatch == null) findBestCategoryBySemanticHint(normalized, candidates) else null
        val finalMatch = strictMatch ?: semanticMatch
        Logger.d(
            ctx,
            "AIService",
            "Category refine mapped: remark=${remark.take(32)}, raw=${rawCate.ifBlank { "<empty>" }}, normalized=${normalized.ifBlank { "<empty>" }}, strict=${strictMatch ?: "<none>"}, semantic=${semanticMatch ?: "<none>"}, final=${finalMatch ?: "<empty>"}"
        )
        return finalMatch
    }

    private fun resolveCategoryByLocalRules(
        matchedRules: List<DbAiRule>,
        candidates: List<String>
    ): String? {
        matchedRules.asSequence()
            .mapNotNull { rule -> rule.targetCategory?.trim().takeIf { !it.isNullOrBlank() } }
            .forEach { target ->
                val normalized = target
                    .replace(" > ", "/::/")
                    .replace(" - ", "/::/")
                    .replace(" / ", "/::/")
                    .trim()
                val matched = findBestMatch(normalized, candidates)
                if (!matched.isNullOrBlank()) return matched
        }
        return null
    }

    private fun resolveLocalRulePrefill(matchedRules: List<DbAiRule>): AILocalRulePrefill? {
        if (matchedRules.isEmpty()) return null
        var type: Int? = null
        var category: String? = null
        var assetName: String? = null
        var toAssetName: String? = null
        matchedRules.forEach { rule ->
            rule.targetType?.takeIf { it in 0..3 }?.let { type = if (it == 3) 2 else it }
            rule.targetCategory?.takeIf { it.isNotBlank() }?.let { category = it }
            rule.targetAccount1?.takeIf { it.isNotBlank() }?.let { assetName = it }
            rule.targetAccount2?.takeIf { it.isNotBlank() }?.let { toAssetName = it }
        }
        return AILocalRulePrefill(type, category, assetName, toAssetName)
    }

    private fun findBestCategoryBySemanticHint(input: String, candidates: List<String>): String? {
        val normalizedInput = input
            .replace(" ", "")
            .replace("分类", "")
            .replace("类目", "")
            .replace("类", "")
            .trim()
        if (normalizedInput.length < 2) return null
        candidates.firstOrNull { candidate ->
            val full = categoryToken(candidate)
            val leaf = categoryToken(candidate.substringAfterLast("/::/"))
            (leaf.length >= 2 && (normalizedInput.contains(leaf) || leaf.contains(normalizedInput))) ||
                (full.length >= 2 && (normalizedInput.contains(full) || full.contains(normalizedInput)))
        }?.let { return it }
        return null
    }

    private fun categoryToken(value: String): String =
        value.lowercase(Locale.ROOT).replace(" ", "")

    private fun buildRawRequest(request: ChatRequest): com.google.gson.JsonObject {
        val gson = com.google.gson.GsonBuilder()
            .registerTypeAdapter(MessageUnion::class.java, MessageUnionSerializer())
            .create()
        return gson.fromJson(gson.toJson(request), com.google.gson.JsonObject::class.java)
    }
}
