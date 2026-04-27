package tao.test.flipaccounting

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tao.test.flipaccounting.data.local.AppDatabase
import tao.test.flipaccounting.data.local.entity.Asset
import tao.test.flipaccounting.data.local.entity.AiRule as DbAiRule
import tao.test.flipaccounting.data.local.entity.Bill
import tao.test.flipaccounting.data.repository.CategoryRepository
import tao.test.flipaccounting.logic.CurrencyManager
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
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

// OCR 模式常量
const val OCR_MODE_LOCAL      = 0   // 本地 ML Kit OCR + 文本 AI
const val OCR_MODE_MULTIMODAL = 1   // 直接多模态 AI（发送图片）

object AIService {
    private const val LOCAL_RULE_APPLIED_FLAG = "_local_rule_applied"
    private const val LOCAL_RULE_CORRECTED_FLAG = "_local_rule_corrected"

    private data class LocalRulePrefill(
        val type: Int? = null,
        val category: String? = null,
        val assetName: String? = null,
        val toAssetName: String? = null
    )

    private data class LocalRuleApplyResult(
        val applied: Boolean,
        val corrected: Boolean,
        val correctedFields: List<String>,
        val changedFields: List<String>
    )

    private data class AccountingPromptContext(
        val dbAssets: List<Asset>,
        val assetInfoList: List<Map<String, String>>,
        val expenseCats: List<String>,
        val incomeCats: List<String>,
        val currencies: List<String>,
        val currentTimeStr: String,
        val assetFeatureEnabled: Boolean
    )

    // 兼容旧版配置页引用（AiConfigActivity 等地方直接用 AIService.XXX 引用这些常量）
    const val DEFAULT_PROMPT                     = AIPrompts.SINGLE_BILL_PROMPT_DEFAULT
    const val MULTI_BILL_PROMPT                  = AIPrompts.MULTI_BILL_PROMPT_DEFAULT
    const val SINGLE_BILL_PROMPT_DEFAULT         = AIPrompts.SINGLE_BILL_PROMPT_DEFAULT
    const val MULTI_BILL_PROMPT_DEFAULT          = AIPrompts.MULTI_BILL_PROMPT_DEFAULT
    const val RULE_EXTRACT_PROMPT_DEFAULT        = AIPrompts.RULE_EXTRACT_PROMPT_DEFAULT
    const val RECEIPT_BILL_PROMPT                = AIPrompts.RECEIPT_BILL_PROMPT
    const val RECEIPT_BILL_PROMPT_CN             = AIPrompts.RECEIPT_BILL_PROMPT_CN
    const val RECEIPT_BILL_PROMPT_FOREIGN        = AIPrompts.RECEIPT_BILL_PROMPT_FOREIGN
    const val RECEIPT_VISION_RETRY_PROMPT_DEFAULT= AIPrompts.RECEIPT_VISION_RETRY_PROMPT_DEFAULT
    const val SCREEN_ACCOUNTING_PROMPT_DEFAULT   = """
你是手机账单截图记账助手。
你会收到一张手机界面截图。你的任务是从截图中只提取真实可记账的交易信息，直接输出最终账单 JSON。

【数据源】
- 资产库（含币种）: {{ASSETS}}
- 支出分类: {{EXPENSE_CATS}}
- 收入分类: {{INCOME_CATS}}
- 当前时间: {{TIME}}
- 可用货币: {{CURRENCIES}}

【识别要求】
1. 输入是手机屏幕截图，不是小票。请忽略页面标题、导航栏、搜索栏、筛选条件、统计汇总、广告、按钮、图标、页脚、浮层等非交易内容。
2. 【重要】识别时只依据截图中的文字内容，完全忽略所有图标、Logo、商家图片、产品图片等图像元素。图标或图片旁边的文字不代表支付方式，除非该文字同时出现在"支付方式""付款方式"等标签之后。
3. 只保留截图中真实存在且可确认的账单/交易信息，不要臆造金额、时间、商户、账户或分类。
4. 金额必须与截图中真实交易对应，不能把汇总统计金额当作单条账单。
5. 若截图中存在多条交易，按真实条目逐条提取；若只有一条明确交易，只提取这一条。
6. remarks 仅保留最关键的消费语义关键词，尽量短（建议不超过 12 个字），不要写完整叙述句。
7. 若无法确认截图里存在可记账内容，请返回 {"no_bill":true,"reply":"未识别到可记账内容"}。

【资产识别规则（严格约束）】
asset_name 的候选来源只有一个：截图中明确标注为“支付方式”、“付款方式”、“付款账户”等的标签，且该标签在视觉上与其后的文字形成“标签：值”对应关系。除此之外页面上出现的任何文字，一律不得用来推断资产。
1. 如果截图中存在上述标签：取该标签紧随其后的文字，与资产库每条 name 做模糊匹配（支持简称，如“招商”匹配“招商銀行”）；匹配成功则输出资产库中该条的 name 原文。
2. 如果截图中不存在上述标签，或标签后的文字与资产库任一条无法匹配，asset_name 必须输出空字符串 ""。
3. asset_name 只能输出资产库中某条的 name 原文，一字不差，禁止任何自创。

【分类规则（严格约束）】
上方分类列表 {{EXPENSE_CATS}} / {{INCOME_CATS}} 列出了所有合法分类，category_name 只能输出其中某条的原文，一字不差，禁止任何自创分类。
1. 优先命中更细的子分类；子分类格式固定为 一级/::/二级，必须与列表原文完全一致。
2. 若列表中找不到完全匹配的子分类，则只输出一级分类名（必须是列表中存在的原文）。
3. 若列表中连一级分类也无法匹配，支出固定填“其他”，收入固定填“其他”。
4. 严禁输出列表中不存在的分类或子分类名称（例如列表中没有“外卖”，就绝对不能输出“外卖”）。

【输出要求】
- 若无法识别可记账内容，返回：{"no_bill":true,"reply":"未识别到可记账内容"}
- 单账单模式时，只返回一个 JSON 对象，字段固定如下：
  {"amount":0.0,"type":0,"asset_name":"","category_name":"","time":"yyyy-MM-dd HH:mm:ss","remarks":"","currency":"CNY","to_asset_name":"","fee":0.0}
- 多账单模式时，只返回如下格式：
  {"bills":[{"amount":0.0,"type":0,"asset_name":"","category_name":"","time":"yyyy-MM-dd HH:mm:ss","remarks":"","currency":"CNY","to_asset_name":"","fee":0.0}]}
- 禁止输出 Markdown、代码块、解释或任何额外文本。
- 禁止输出上述字段之外的任何字段（如 payee、transaction_id、status 等）。
"""
    const val RECEIPT_OCR_REFINE_PROMPT_DEFAULT  = AIPrompts.RECEIPT_OCR_REFINE_PROMPT_DEFAULT
    const val MULTI_BILL_PROMPT_CONCISE          = AIPrompts.MULTI_BILL_PROMPT_CONCISE
    const val CHAT_ASSISTANT_PROMPT_DEFAULT      = AIPrompts.CHAT_ASSISTANT_PROMPT_DEFAULT
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

    private fun normalizeBaseUrl(url: String): String {
        var baseUrl = url
        if (baseUrl.isEmpty()) baseUrl = "https://api.siliconflow.cn/"
        if (!baseUrl.endsWith("/")) baseUrl += "/"
        return baseUrl
    }

    private fun shortenForModel(text: String, maxChars: Int, preserveTail: Boolean = true): String {
        if (text.length <= maxChars) return text
        if (maxChars <= 200) return text.take(maxChars)
        val head = (maxChars * 0.7).toInt()
        val tail = if (preserveTail) maxChars - head - 32 else 0
        return if (preserveTail && tail > 0) {
            text.take(head) + "\n\n[内容过长，已省略中间部分]\n\n" + text.takeLast(tail)
        } else {
            text.take(maxChars) + "\n\n[内容过长，已截断]"
        }
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

    private fun detectSpeechAudioMimeType(audioFile: File): String = when (audioFile.extension.lowercase(Locale.ROOT)) {
        "wav"  -> "audio/wav"
        "m4a"  -> "audio/mp4"
        "mp3"  -> "audio/mpeg"
        "ogg"  -> "audio/ogg"
        "flac" -> "audio/flac"
        else   -> "application/octet-stream"
    }

    private fun detailedHttpError(e: Exception): String {
        if (e is HttpException) {
            val code = e.code()
            val body = runCatching { e.response()?.errorBody()?.string().orEmpty() }.getOrDefault("")
            return if (body.isNotBlank()) "HTTP $code, errorBody=$body" else "HTTP $code, message=${e.message()}"
        }
        return e.message ?: e.javaClass.simpleName
    }

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
        val assetFeatureEnabled = Prefs.isAssetFeatureEnabled(ctx)
        val model = if (isMultiMode) Prefs.getAiMultiModel(ctx) else Prefs.getAiSingleModel(ctx)
        if (apiKey.isEmpty()) throw IllegalArgumentException("请先在设置中配置 API Key")

        val dbAssets = withContext(Dispatchers.IO) {
            AppDatabase.getDatabase(ctx).assetDao().getAllAssetsList()
        }
        val assetInfoList = if (assetFeatureEnabled) {
            dbAssets.map { a ->
                mapOf<String, String>(
                    "name" to a.name,
                    "category" to if (a.assetCategory == Asset.CATEGORY_CREDIT_CARD) "credit_card" else "normal",
                    "currency" to a.currency.ifEmpty { "CNY" }
                )
            }
        } else {
            emptyList()
        }
        val assets = if (assetFeatureEnabled) {
            dbAssets.map { it.name }.ifEmpty { Prefs.getAssets(ctx).map { it.name } }
        } else {
            emptyList()
        }
        val currencies = CurrencyManager.getEnabledCurrencies(ctx)
        val availableBooks = withContext(Dispatchers.IO) {
            val dbBookNames = AppDatabase.getDatabase(ctx).billDao().getAllBookNames()
            BookAccountManager.getBookAccounts(ctx, dbBookNames)
                .map { BookAccountManager.normalizeBookName(it) }
                .filter { it.isNotBlank() && it != BookAccountManager.ALL_BOOK }
                .distinct()
        }

        val catRepo = CategoryRepository(AppDatabase.getDatabase(ctx).categoryDao())
        val expenseCats = mutableListOf<String>()
        withContext(Dispatchers.IO) { catRepo.getCategoryTree(0) }.forEach { parentNode ->
            expenseCats.add(parentNode.name)
            parentNode.subs.forEach { childNode ->
                expenseCats.add("${parentNode.name}/::/${childNode.name}")
            }
        }

        val incomeCats = mutableListOf<String>()
        withContext(Dispatchers.IO) { catRepo.getCategoryTree(1) }.forEach { parentNode ->
            incomeCats.add(parentNode.name)
            parentNode.subs.forEach { childNode ->
                incomeCats.add("${parentNode.name}/::/${childNode.name}")
            }
        }

        val demoAsset      = assets.firstOrNull() ?: "微信"
        val demoExpenseCat = expenseCats.firstOrNull() ?: "其他"
        val demoIncomeCat  = incomeCats.firstOrNull() ?: "工资"
        val expenseLeafCats = expenseCats.map { it.substringAfterLast("/::/") }.distinct()
        val incomeLeafCats = incomeCats.map { it.substringAfterLast("/::/") }.distinct()

        val now = Date()
        val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val weekFormat = SimpleDateFormat("EEEE", Locale.getDefault())
        val currentTimeStr = "${timeFormat.format(now)} (${weekFormat.format(now)})"

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

        var p = if (isMultiMode) Prefs.getMultiBillPrompt(ctx) else Prefs.getAiPrompt(ctx)
        if (p.isEmpty()) p = if (isMultiMode) getDefaultMultiBillPrompt(ctx) else getDefaultSingleBillPrompt(ctx)
        p = adaptPromptForCategoryDepth(
            prompt = p,
            hasSecondLevel = hasSecondLevelCategories(expenseCats, incomeCats)
        )

        p += if (assetFeatureEnabled) {
            "\n\n【类型白名单硬约束】`type` 仅允许四种取值：0=支出，1=收入，2=转账，3=还款。严禁输出其他数字。\n"
        } else {
            "\n\n【无资产模式硬约束】当前账本已关闭资产功能：禁止输出转账、还款、信用卡还款；`type` 仅允许 0=支出 或 1=收入；`asset_name`、`to_asset_name` 必须留空或不输出。\n"
        }
        p += "\n【示例防串用硬约束】系统提示词中的示例日期、示例金额、示例商家名都只是格式示范，绝不能直接抄进当前结果；若用户未明确给出时间，请结合当前时间理解，而不是使用示例中的固定日期。\n"
        p += buildRemarksRichnessRule()
        p += buildIncomeCategoryHardRule()
        if (availableBooks.isNotEmpty()) {
            p += "\n【账本字段（可选）】当且仅当用户明确提到记入某账本时，才可输出 `book_name` 字段；可选账本：${availableBooks.joinToString("、")}。未明确提及时不要猜测，也可以不输出该字段。\n"
        }

        val creditCardNames = dbAssets
            .filter { it.assetCategory == Asset.CATEGORY_CREDIT_CARD }
            .map { it.name }
        if (assetFeatureEnabled && creditCardNames.isNotEmpty()) {
            p += "\n【还款识别规则（高优先）】资产库中以下资产为信用卡账户：${creditCardNames.joinToString("、")}。\n" +
                 "- 当 to_asset_name 指向信用卡账户时，该笔账单为还款，仍输出 type=2（转账），category_name 固定为\"还款\"。\n" +
                 "- \"还信用卡\"、\"还款\"、\"还卡\"、\"credit card payment\"等语义 → type=2，to_asset_name=对应信用卡名，category_name=\"还款\"。\n"
        }

        // 资产币种自动继承规则：
        // 当用户命中资产库中某资产时，该资产的 currency 字段即为对应账单的默认币种。
        // 例：资产库中 "Visa Card" 的 currency=PLN，则涉及该资产的账单 currency 应输出 PLN，而非 CNY。
        val assetsWithNonCnyCurrency = dbAssets.filter { it.currency.isNotEmpty() && it.currency != "CNY" }
        if (assetFeatureEnabled && assetsWithNonCnyCurrency.isNotEmpty()) {
            val assetCurrencyHints = assetsWithNonCnyCurrency.joinToString("、") { "\"${it.name}\"(${it.currency})" }
            p += "\n【资产币种自动继承（强约束）】以下资产已绑定非人民币币种：$assetCurrencyHints。\n" +
                 "- 当 asset_name 命中上述资产时，currency 必须输出该资产对应的币种，而非默认 CNY。\n" +
                 "- 此规则优先级高于「未提及币种默认 CNY」规则。\n"
        }

        if (isMultiMode) {
            p += "\n【购物小票语义强约束】如果输入出现\"购买、花了、总计花费、刷卡、支付、visa、mastercard、receipt、discount\"等购物语义，则相关账单默认判定为支出（type=0）；只有明确出现\"工资、收入、收款、到账、退款到账、报销到账\"等入账语义时，才允许判定为收入（type=1）。\n"
            val isFastMode = Prefs.isMultiBillFastMode(ctx)
            if (isFastMode) {
                p += "\n【极简多账单模式】直接在本轮输出所有账单及完整分类，不会有第二阶段。\n" +
                     "- 每条 bill 必须包含完整字段：amount、type、asset_name、category_name、to_asset_name、time、remarks、currency、fee。\n" +
                     "- remarks 仅保留该条消费的核心关键词，尽量简短（建议 <=12 字）。\n" +
                     "- category_name 从可选分类中选择最合适的一条，支出参考：${expenseLeafCats.joinToString("、")}；收入参考：${incomeLeafCats.joinToString("、")}。\n" +
                     "- 若无法确定分类，输出空字符串，不要瞎猜；优先保证金额和拆单准确。\n"
            } else {
                p += "\n【多账单第一阶段职责】第一阶段只负责拆单和提取基础字段，不负责最终分类。\n" +
                     "- 每条 bill 必须优先保证 amount、type、asset_name、to_asset_name、time、remarks、currency、fee 正确。\n" +
                     "- remarks 只保留能区分该条消费的核心关键词，尽量简短（建议 <=12 字），便于下一阶段分类。\n" +
                     "- category_name 在第一阶段可以留空，或仅在你非常确定时填写；不要为了凑字段而勉强分类，更不要把多条商品统一归成同一类。\n" +
                     "- 如果一句话里有多个商品/事项，先拆成多条，再交给下一阶段逐条分类。\n"
                p += "\n【第二阶段分类提示】后续会按每条 remarks 单独判断最终分类。支出可用叶子分类示例：${expenseLeafCats.joinToString("、")}。收入可用叶子分类示例：${incomeLeafCats.joinToString("、")}。\n"
            }
        }

        if (isMultiMode && !Prefs.isMultiBillFastMode(ctx)) {
            p += "\n【多账单两阶段处理】当前为多账单模式。第一阶段的首要目标是把整段话拆成多条 bill，并尽量提取准确的 amount、type、asset_name、to_asset_name、time、remarks、currency、fee。\n" +
                 "- remarks 仅保留关键语义词，避免长句描述（建议 <=12 字）。\n" +
                 "- 如果分类一时拿不准，优先保证拆单和 remarks 正确；后续会基于每条 remarks 再做逐条分类。\n"
        }

        if (matchedPromptRules.isNotEmpty()) {
            p += buildPromptCorrectionBlock(matchedPromptRules, includeCategory = !isMultiMode)
        }
        if (!isMultiMode && localPrefill != null) {
            p += "\n【本地规则预匹配】本次输入已命中本地记账习惯。\n" +
                 "- 已预设字段会在后续本地规则中补全或校正，AI 本轮重点只需要抽取金额、时间、备注、币种、手续费等基础信息。\n" +
                 "- 如果分类或账户拿不准，可以留空，不要为了凑字段勉强猜测。\n"
        }
        p += "\n【输出格式】You must return one valid JSON object only. 可选字段：book_name、target_amount、target_currency（仅在用户明确提到到账金额时输出）。Do not return markdown or extra explanation.\n"

        val promptExpenseCats = if (!isMultiMode && !localPrefill?.category.isNullOrBlank()) emptyList() else expenseCats
        val promptIncomeCats = if (!isMultiMode && !localPrefill?.category.isNullOrBlank()) emptyList() else incomeCats
        val promptAssets = if (!isMultiMode && (!localPrefill?.assetName.isNullOrBlank() || !localPrefill?.toAssetName.isNullOrBlank())) emptyList() else assetInfoList

        val systemPrompt = p
            .replace("{{TIME}}", currentTimeStr)
            .replace("{{ASSETS}}", Gson().toJson(promptAssets))
            .replace("{{EXPENSE_CATS}}", Gson().toJson(promptExpenseCats))
            .replace("{{INCOME_CATS}}", Gson().toJson(promptIncomeCats))
            .replace("{{CURRENCIES}}", Gson().toJson(currencies))
            .replace("{{DEMO_ASSET}}", demoAsset)
            .replace("{{DEMO_EXPENSE_CAT}}", demoExpenseCat)
            .replace("{{DEMO_INCOME_CAT}}", demoIncomeCat)

        return try {
            val requestJson = com.google.gson.JsonObject().apply {
                addProperty("model", model)
                addProperty("temperature", 0.3)
                    add("messages", com.google.gson.JsonArray().apply {
                        add(com.google.gson.JsonObject().apply { addProperty("role", "system"); addProperty("content", systemPrompt) })
                    add(com.google.gson.JsonObject().apply { addProperty("role", "user"); addProperty("content", safeUserInput) })
                })
            }
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
                if (!assetFeatureEnabled) {
                    enforceNoAssetMode(root)
                }
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
                        expenseCats = expenseCats,
                        incomeCats = incomeCats,
                        currentTimeStr = currentTimeStr,
                        assetInfoList = assetInfoList,
                        allPromptRules = promptRules,
                        onProgress = onProgress
                    )
                }

                // ── 第2步：分类合法性修正（把 AI 返回的分类映射到本地分类库）──
                if (root.has("bills")) {
                    val billsArr = root.getJSONArray("bills")
                    for (i in 0 until billsArr.length()) {
                        val b = billsArr.getJSONObject(i)
                        val rawType = b.optInt("type", 0)
                        val type = normalizeBillType(rawType)
                        b.put("type", type)
                        if (type == 2) {
                            if (rawType == 3 || b.optInt("subType", 0) == Bill.SUBTYPE_REPAYMENT || b.optString("category_name") == "还款") {
                                b.put("subType", Bill.SUBTYPE_REPAYMENT)
                                b.put("category_name", "还款")
                            } else if (b.optString("category_name").isBlank()) {
                                b.put("category_name", "转账")
                            }
                            continue
                        }
                        val candidates = if (type == 1) incomeCats else expenseCats
                        val rawCate = b.optString("category_name", "")
                        val normalized = rawCate.replace(" > ", "/::/").replace(" - ", "/::/").replace(" / ", "/::/").trim()
                        val matched = findBestMatch(normalized, candidates)
                        if (matched != null) b.put("category_name", matched)
                        else if (normalized.isNotEmpty()) {
                            val fallback = resolveOtherCategory(candidates)
                            if (!fallback.isNullOrBlank()) b.put("category_name", fallback)
                            else b.put("category_name", "")
                        }
                    }
                } else if (root.has("amount")) {
                    val rawType = root.optInt("type", 0)
                    val type = normalizeBillType(rawType)
                    root.put("type", type)
                    if (type == 2) {
                        if (rawType == 3 || root.optInt("subType", 0) == Bill.SUBTYPE_REPAYMENT || root.optString("category_name") == "还款") {
                            root.put("subType", Bill.SUBTYPE_REPAYMENT)
                            root.put("category_name", "还款")
                        } else if (root.optString("category_name").isBlank()) {
                            root.put("category_name", "转账")
                        }
                    } else {
                        val candidates = if (type == 1) incomeCats else expenseCats
                        val rawCate = root.optString("category_name", "")
                        val normalized = rawCate.replace(" > ", "/::/").replace(" - ", "/::/").replace(" / ", "/::/").trim()
                        val matched = findBestMatch(normalized, candidates)
                        if (matched != null) root.put("category_name", matched)
                        else if (normalized.isNotEmpty()) {
                            val fallback = resolveOtherCategory(candidates)
                            if (!fallback.isNullOrBlank()) root.put("category_name", fallback)
                            else root.put("category_name", "")
                        }
                    }
                }

                if (assetFeatureEnabled) {
                    val assetNames = dbAssets.map { it.name }.filter { it.isNotBlank() }
                    normalizeMisplacedAssetOnExpenseOrIncome(root, assetNames)
                    enforceTransferRequiresValidAssets(root, assetNames, expenseCats)
                }

            }
            result
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Logger.d(ctx, "AIService", "AI Request Failed: ${detailedHttpError(e)}")
            throw e
        }
    }

    private suspend fun buildAccountingPromptContext(ctx: Context): AccountingPromptContext {
        val assetFeatureEnabled = Prefs.isAssetFeatureEnabled(ctx)
        val dbAssets = withContext(Dispatchers.IO) {
            AppDatabase.getDatabase(ctx).assetDao().getAllAssetsList()
        }
        val assetInfoList = if (assetFeatureEnabled) {
            dbAssets.map { a ->
                mapOf(
                    "name" to a.name,
                    "category" to if (a.assetCategory == Asset.CATEGORY_CREDIT_CARD) "credit_card" else "normal",
                    "currency" to a.currency.ifEmpty { "CNY" }
                )
            }
        } else {
            emptyList()
        }
        val catRepo = CategoryRepository(AppDatabase.getDatabase(ctx).categoryDao())
        val expenseCats = mutableListOf<String>()
        withContext(Dispatchers.IO) { catRepo.getCategoryTree(0) }.forEach { parentNode ->
            expenseCats.add(parentNode.name)
            parentNode.subs.forEach { childNode ->
                expenseCats.add("${parentNode.name}/::/${childNode.name}")
            }
        }
        val incomeCats = mutableListOf<String>()
        withContext(Dispatchers.IO) { catRepo.getCategoryTree(1) }.forEach { parentNode ->
            incomeCats.add(parentNode.name)
            parentNode.subs.forEach { childNode ->
                incomeCats.add("${parentNode.name}/::/${childNode.name}")
            }
        }
        val now = Date()
        val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val weekFormat = SimpleDateFormat("EEEE", Locale.getDefault())
        val currentTimeStr = "${timeFormat.format(now)} (${weekFormat.format(now)})"
        return AccountingPromptContext(
            dbAssets = dbAssets,
            assetInfoList = assetInfoList,
            expenseCats = expenseCats,
            incomeCats = incomeCats,
            currencies = CurrencyManager.getEnabledCurrencies(ctx),
            currentTimeStr = currentTimeStr,
            assetFeatureEnabled = assetFeatureEnabled
        )
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
        val assetFeatureEnabled = Prefs.isAssetFeatureEnabled(ctx)
        val model = if (isMultiMode) Prefs.getAiMultiModel(ctx) else Prefs.getAiSingleModel(ctx)
        if (apiKey.isEmpty()) throw IllegalArgumentException("请先在设置中配置 API Key")

        val dbAssets = withContext(Dispatchers.IO) {
            AppDatabase.getDatabase(ctx).assetDao().getAllAssetsList()
        }
        val assetInfoList = if (assetFeatureEnabled) {
            dbAssets.map { a ->
                mapOf(
                    "name" to a.name,
                    "category" to if (a.assetCategory == Asset.CATEGORY_CREDIT_CARD) "credit_card" else "normal",
                    "currency" to a.currency.ifEmpty { "CNY" }
                )
            }
        } else {
            emptyList()
        }
        val assets = if (assetFeatureEnabled) {
            dbAssets.map { it.name }.ifEmpty { Prefs.getAssets(ctx).map { it.name } }
        } else {
            emptyList()
        }
        val currencies = CurrencyManager.getEnabledCurrencies(ctx)
        val availableBooks = withContext(Dispatchers.IO) {
            val dbBookNames = AppDatabase.getDatabase(ctx).billDao().getAllBookNames()
            BookAccountManager.getBookAccounts(ctx, dbBookNames)
                .map { BookAccountManager.normalizeBookName(it) }
                .filter { it.isNotBlank() && it != BookAccountManager.ALL_BOOK }
                .distinct()
        }

        val catRepo = CategoryRepository(AppDatabase.getDatabase(ctx).categoryDao())
        val expenseCats = mutableListOf<String>()
        withContext(Dispatchers.IO) { catRepo.getCategoryTree(0) }.forEach { parentNode ->
            expenseCats.add(parentNode.name)
            parentNode.subs.forEach { childNode ->
                expenseCats.add("${parentNode.name}/::/${childNode.name}")
            }
        }

        val incomeCats = mutableListOf<String>()
        withContext(Dispatchers.IO) { catRepo.getCategoryTree(1) }.forEach { parentNode ->
            incomeCats.add(parentNode.name)
            parentNode.subs.forEach { childNode ->
                incomeCats.add("${parentNode.name}/::/${childNode.name}")
            }
        }

        val demoAsset = assets.firstOrNull() ?: "微信"
        val demoExpenseCat = expenseCats.firstOrNull() ?: "其他"
        val demoIncomeCat = incomeCats.firstOrNull() ?: "工资"
        val expenseLeafCats = expenseCats.map { it.substringAfterLast("/::/") }.distinct()
        val incomeLeafCats = incomeCats.map { it.substringAfterLast("/::/") }.distinct()

        val now = Date()
        val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val weekFormat = SimpleDateFormat("EEEE", Locale.getDefault())
        val currentTimeStr = "${timeFormat.format(now)} (${weekFormat.format(now)})"
        val promptRules = loadActivePromptRules(ctx)

        var p = if (isMultiMode) Prefs.getMultiBillPrompt(ctx) else Prefs.getAiPrompt(ctx)
        if (p.isEmpty()) p = if (isMultiMode) getDefaultMultiBillPrompt(ctx) else getDefaultSingleBillPrompt(ctx)
        p = adaptPromptForCategoryDepth(
            prompt = p,
            hasSecondLevel = hasSecondLevelCategories(expenseCats, incomeCats)
        )

        p += if (assetFeatureEnabled) {
            "\n\n【类型白名单硬约束】`type` 仅允许四种取值：0=支出，1=收入，2=转账，3=还款。严禁输出其他数字。\n"
        } else {
            "\n\n【无资产模式硬约束】当前账本已关闭资产功能：禁止输出转账、还款、信用卡还款；`type` 仅允许 0=支出 或 1=收入；`asset_name`、`to_asset_name` 必须留空或不输出。\n"
        }
        p += "\n【语音输入说明】本轮用户输入为一段口述记账语音，请直接根据语音内容提取账单，不要要求用户重新输入文字。\n"
        p += "\n【示例防串用硬约束】系统提示词中的示例日期、示例金额、示例商家名都只是格式示范，绝不能直接抄进当前结果；若用户未明确给出时间，请结合当前时间理解，而不是使用示例中的固定日期。\n"
        p += buildRemarksRichnessRule()
        p += buildIncomeCategoryHardRule()
        if (availableBooks.isNotEmpty()) {
            p += "\n【账本字段（可选）】当且仅当用户明确提到记入某账本时，才可输出 `book_name` 字段；可选账本：${availableBooks.joinToString("、")}。未明确提及时不要猜测，也可以不输出该字段。\n"
        }

        val creditCardNames = dbAssets
            .filter { it.assetCategory == Asset.CATEGORY_CREDIT_CARD }
            .map { it.name }
        if (assetFeatureEnabled && creditCardNames.isNotEmpty()) {
            p += "\n【还款识别规则（高优先）】资产库中以下资产为信用卡账户：${creditCardNames.joinToString("、")}。\n" +
                "- 当 to_asset_name 指向信用卡账户时，该笔账单为还款，仍输出 type=2（转账），category_name 固定为\"还款\"。\n" +
                "- \"还信用卡\"、\"还款\"、\"还卡\"、\"credit card payment\"等语义 → type=2，to_asset_name=对应信用卡名，category_name=\"还款\"。\n"
        }

        val assetsWithNonCnyCurrency = dbAssets.filter { it.currency.isNotEmpty() && it.currency != "CNY" }
        if (assetFeatureEnabled && assetsWithNonCnyCurrency.isNotEmpty()) {
            val assetCurrencyHints = assetsWithNonCnyCurrency.joinToString("、") { "\"${it.name}\"(${it.currency})" }
            p += "\n【资产币种自动继承（强约束）】以下资产已绑定非人民币币种：$assetCurrencyHints。\n" +
                "- 当 asset_name 命中上述资产时，currency 必须输出该资产对应的币种，而非默认 CNY。\n" +
                "- 此规则优先级高于「未提及币种默认 CNY」规则。\n"
        }

        if (isMultiMode) {
            p += "\n【购物小票语义强约束】如果输入出现\"购买、花了、总计花费、刷卡、支付、visa、mastercard、receipt、discount\"等购物语义，则相关账单默认判定为支出（type=0）；只有明确出现\"工资、收入、收款、到账、退款到账、报销到账\"等入账语义时，才允许判定为收入（type=1）。\n"
            val isFastMode = Prefs.isMultiBillFastMode(ctx)
            if (isFastMode) {
                p += "\n【极简多账单模式】直接在本轮输出所有账单及完整分类，不会有第二阶段。\n" +
                     "- 每条 bill 必须包含完整字段：amount、type、asset_name、category_name、to_asset_name、time、remarks、currency、fee。\n" +
                     "- remarks 仅保留该条消费的核心关键词，尽量简短（建议 <=12 字）。\n" +
                     "- category_name 从可选分类中选择最合适的一条，支出参考：${expenseLeafCats.joinToString("、")}；收入参考：${incomeLeafCats.joinToString("、")}。\n" +
                     "- 若无法确定分类，输出空字符串，不要瞎猜；优先保证金额和拆单准确。\n"
            } else {
                p += "\n【多账单第一阶段职责】第一阶段只负责拆单和提取基础字段，不负责最终分类。\n" +
                    "- 每条 bill 必须优先保证 amount、type、asset_name、to_asset_name、time、remarks、currency、fee 正确。\n" +
                    "- remarks 只保留能区分该条消费的核心关键词，尽量简短（建议 <=12 字），便于下一阶段分类。\n" +
                    "- category_name 在第一阶段可以留空，或仅在你非常确定时填写；不要为了凑字段而勉强分类，更不要把多条商品统一归成同一类。\n" +
                    "- 如果一句话里有多个商品/事项，先拆成多条，再交给下一阶段逐条分类。\n"
                p += "\n【第二阶段分类提示】后续会按每条 remarks 单独判断最终分类。支出可用叶子分类示例：${expenseLeafCats.joinToString("、")}。收入可用叶子分类示例：${incomeLeafCats.joinToString("、")}。\n"
                p += "\n【多账单两阶段处理】当前为多账单模式。第一阶段的首要目标是把整段话拆成多条 bill，并尽量提取准确的 amount、type、asset_name、to_asset_name、time、remarks、currency、fee。\n" +
                    "- remarks 仅保留关键语义词，避免长句描述（建议 <=12 字）。\n" +
                    "- 如果分类一时拿不准，优先保证拆单和 remarks 正确；后续会基于每条 remarks 再做逐条分类。\n"
            }
        }
        p += "\n【输出格式】You must return one valid JSON object only. 可选字段：book_name。Do not return markdown or extra explanation.\n"

        val systemPrompt = p
            .replace("{{TIME}}", currentTimeStr)
            .replace("{{ASSETS}}", Gson().toJson(assetInfoList))
            .replace("{{EXPENSE_CATS}}", Gson().toJson(expenseCats))
            .replace("{{INCOME_CATS}}", Gson().toJson(incomeCats))
            .replace("{{CURRENCIES}}", Gson().toJson(currencies))
            .replace("{{DEMO_ASSET}}", demoAsset)
            .replace("{{DEMO_EXPENSE_CAT}}", demoExpenseCat)
            .replace("{{DEMO_INCOME_CAT}}", demoIncomeCat)

        val audioBase64 = withContext(Dispatchers.IO) {
            Base64.encodeToString(audioFile.readBytes(), Base64.NO_WRAP)
        }
        val audioFormat = audioFile.extension.lowercase(Locale.ROOT).ifBlank { "wav" }

        return try {
            val requestJson = com.google.gson.JsonObject().apply {
                addProperty("model", model)
                addProperty("temperature", 0.3)
                add("messages", com.google.gson.JsonArray().apply {
                    add(com.google.gson.JsonObject().apply {
                        addProperty("role", "system")
                        addProperty("content", systemPrompt)
                    })
                    add(com.google.gson.JsonObject().apply {
                        addProperty("role", "user")
                        add("content", com.google.gson.JsonArray().apply {
                            add(com.google.gson.JsonObject().apply {
                                addProperty("type", "text")
                                addProperty("text", "这是一段用户口述记账语音。请严格按系统提示词要求提取账单 JSON。")
                            })
                            add(com.google.gson.JsonObject().apply {
                                addProperty("type", "input_audio")
                                add("input_audio", com.google.gson.JsonObject().apply {
                                    addProperty("data", audioBase64)
                                    addProperty("format", audioFormat)
                                })
                            })
                        })
                    })
                })
            }
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
                if (!assetFeatureEnabled) {
                    enforceNoAssetMode(root)
                }
                if (isMultiMode && root.has("bills") && !Prefs.isMultiBillFastMode(ctx)) {
                    refineMultiBillCategories(
                        ctx = ctx,
                        root = root,
                        expenseCats = expenseCats,
                        incomeCats = incomeCats,
                        currentTimeStr = currentTimeStr,
                        assetInfoList = assetInfoList,
                        allPromptRules = promptRules,
                        onProgress = onProgress
                    )
                }

                if (root.has("bills")) {
                    val billsArr = root.getJSONArray("bills")
                    for (i in 0 until billsArr.length()) {
                        val b = billsArr.getJSONObject(i)
                        val rawType = b.optInt("type", 0)
                        val type = normalizeBillType(rawType)
                        b.put("type", type)
                        if (type == 2) {
                            if (rawType == 3 || b.optInt("subType", 0) == Bill.SUBTYPE_REPAYMENT || b.optString("category_name") == "还款") {
                                b.put("subType", Bill.SUBTYPE_REPAYMENT)
                                b.put("category_name", "还款")
                            } else if (b.optString("category_name").isBlank()) {
                                b.put("category_name", "转账")
                            }
                            continue
                        }
                        val candidates = if (type == 1) incomeCats else expenseCats
                        val rawCate = b.optString("category_name", "")
                        val normalized = rawCate.replace(" > ", "/::/").replace(" - ", "/::/").replace(" / ", "/::/").trim()
                        val matched = findBestMatch(normalized, candidates)
                        if (matched != null) b.put("category_name", matched)
                        else if (normalized.isNotEmpty()) {
                            val fallback = resolveOtherCategory(candidates)
                            if (!fallback.isNullOrBlank()) b.put("category_name", fallback)
                            else b.put("category_name", "")
                        }
                    }
                } else if (root.has("amount")) {
                    val rawType = root.optInt("type", 0)
                    val type = normalizeBillType(rawType)
                    root.put("type", type)
                    if (type == 2) {
                        if (rawType == 3 || root.optInt("subType", 0) == Bill.SUBTYPE_REPAYMENT || root.optString("category_name") == "还款") {
                            root.put("subType", Bill.SUBTYPE_REPAYMENT)
                            root.put("category_name", "还款")
                        } else if (root.optString("category_name").isBlank()) {
                            root.put("category_name", "转账")
                        }
                    } else {
                        val candidates = if (type == 1) incomeCats else expenseCats
                        val rawCate = root.optString("category_name", "")
                        val normalized = rawCate.replace(" > ", "/::/").replace(" - ", "/::/").replace(" / ", "/::/").trim()
                        val matched = findBestMatch(normalized, candidates)
                        if (matched != null) root.put("category_name", matched)
                        else if (normalized.isNotEmpty()) {
                            val fallback = resolveOtherCategory(candidates)
                            if (!fallback.isNullOrBlank()) root.put("category_name", fallback)
                            else root.put("category_name", "")
                        }
                    }
                }

                if (assetFeatureEnabled) {
                    val assetNames = dbAssets.map { it.name }.filter { it.isNotBlank() }
                    normalizeMisplacedAssetOnExpenseOrIncome(root, assetNames)
                    enforceTransferRequiresValidAssets(root, assetNames, expenseCats)
                }
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

        val requestJson = com.google.gson.JsonObject().apply {
            addProperty("model", model)
            addProperty("temperature", 0.3)
            add("messages", com.google.gson.JsonArray().apply {
                add(com.google.gson.JsonObject().apply { addProperty("role", "system"); addProperty("content", systemPrompt) })
                add(com.google.gson.JsonObject().apply {
                    addProperty("role", "user")
                    add("content", com.google.gson.JsonArray().apply {
                        add(com.google.gson.JsonObject().apply {
                            addProperty("type", "image_url")
                            add("image_url", com.google.gson.JsonObject().apply { addProperty("url", dataUrl) })
                        })
                        add(com.google.gson.JsonObject().apply {
                            addProperty("type", "text")
                            addProperty("text", "请分析这张小票订单图片，转为自然语言清单。")
                        })
                    })
                })
            })
        }

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
            val requestJson = com.google.gson.JsonObject().apply {
                addProperty("model", model)
                addProperty("temperature", 0.1)
                add("messages", com.google.gson.JsonArray().apply {
                    add(com.google.gson.JsonObject().apply {
                        addProperty("role", "user")
                        add("content", com.google.gson.JsonArray().apply {
                            add(com.google.gson.JsonObject().apply {
                                addProperty("type", "image_url")
                                add("image_url", com.google.gson.JsonObject().apply {
                                    addProperty("url", "data:image/png;base64,${buildProbeImageBase64(ctx)}")
                                })
                            })
                            add(com.google.gson.JsonObject().apply {
                                addProperty("type", "text")
                                addProperty("text", "Reply with OK only.")
                            })
                        })
                    })
                })
            }
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
        val demoAsset = promptContext.assetInfoList.firstOrNull()?.get("name") ?: "微信"
        val demoExpenseCat = promptContext.expenseCats.firstOrNull() ?: "其他"
        val demoIncomeCat = promptContext.incomeCats.firstOrNull() ?: "工资"
        val expenseLeafCats = promptContext.expenseCats.map { it.substringAfterLast("/::/") }.distinct()
        val incomeLeafCats = promptContext.incomeCats.map { it.substringAfterLast("/::/") }.distinct()

        var p = Prefs.getScreenAccountingPrompt(ctx).ifBlank { SCREEN_ACCOUNTING_PROMPT_DEFAULT }
        p = adaptPromptForCategoryDepth(
            prompt = p,
            hasSecondLevel = hasSecondLevelCategories(promptContext.expenseCats, promptContext.incomeCats)
        )
        p += buildRemarksRichnessRule()
        p += buildIncomeCategoryHardRule()

        p += if (promptContext.assetFeatureEnabled) {
            "\n\n【类型白名单硬约束】`type` 仅允许四种取值：0=支出，1=收入，2=转账，3=还款。严禁输出其他数字。\n"
        } else {
            "\n\n【无资产模式硬约束】当前账本已关闭资产功能：禁止输出转账、还款、信用卡还款；`type` 仅允许 0=支出 或 1=收入；`asset_name`、`to_asset_name` 必须留空或不输出。\n"
        }

        val creditCardNames = promptContext.dbAssets
            .filter { it.assetCategory == Asset.CATEGORY_CREDIT_CARD }
            .map { it.name }
        if (promptContext.assetFeatureEnabled && creditCardNames.isNotEmpty()) {
            p += "\n【还款识别规则（高优先）】资产库中以下资产为信用卡账户：${creditCardNames.joinToString("、")}。\n" +
                "- 当 to_asset_name 指向信用卡账户时，该笔账单为还款，仍输出 type=2（转账），category_name 固定为\"还款\"。\n" +
                "- \"还信用卡\"、\"还款\"、\"还卡\"、\"credit card payment\"等语义 → type=2，to_asset_name=对应信用卡名，category_name=\"还款\"。\n"
        }

        val assetsWithNonCnyCurrency = promptContext.dbAssets.filter { it.currency.isNotEmpty() && it.currency != "CNY" }
        if (promptContext.assetFeatureEnabled && assetsWithNonCnyCurrency.isNotEmpty()) {
            val assetCurrencyHints = assetsWithNonCnyCurrency.joinToString("、") { "\"${it.name}\"(${it.currency})" }
            p += "\n【资产币种自动继承（强约束）】以下资产已绑定非人民币币种：$assetCurrencyHints。\n" +
                "- 当 asset_name 命中上述资产时，currency 必须输出该资产对应的币种，而非默认 CNY。\n" +
                "- 此规则优先级高于「未提及币种默认 CNY」规则。\n"
        }

        if (isMultiMode) {
            p += "\n【多账单截图模式】当前为多账单模式。若截图中存在多条真实交易，请按真实条目逐条输出 bills；若只有一条交易，也可输出单条 bill 组成的 bills 数组。\n"
            p += "\n【分类提示】支出可用叶子分类示例：${expenseLeafCats.joinToString("、")}。收入可用叶子分类示例：${incomeLeafCats.joinToString("、")}。\n"
            p += "\n【输出格式】必须只返回 {\"bills\":[...]}，每条字段固定为 amount,type,asset_name,category_name,time,remarks,currency,to_asset_name,fee。不要输出额外说明。\n"
        } else {
            p += "\n【单账单截图模式】当前为单账单模式。即使截图中看起来有多条交易，也只提取最明确、最主要的一条交易；无法确定主交易时返回 no_bill。\n"
            p += "\n【输出格式】必须只返回一个 JSON 对象，字段固定为 amount,type,asset_name,category_name,time,remarks,currency,to_asset_name,fee；或返回 no_bill 对象。不要输出额外说明。\n"
        }
        p += "\n【输出格式】You must return one valid JSON object only. Do not return markdown or extra explanation.\n"

        val systemPrompt = p
            .replace("{{TIME}}", promptContext.currentTimeStr)
            .replace("{{ASSETS}}", Gson().toJson(promptContext.assetInfoList))
            .replace("{{EXPENSE_CATS}}", Gson().toJson(promptContext.expenseCats))
            .replace("{{INCOME_CATS}}", Gson().toJson(promptContext.incomeCats))
            .replace("{{CURRENCIES}}", Gson().toJson(promptContext.currencies))
            .replace("{{DEMO_ASSET}}", demoAsset)
            .replace("{{DEMO_EXPENSE_CAT}}", demoExpenseCat)
            .replace("{{DEMO_INCOME_CAT}}", demoIncomeCat)

        val dataUrl = "data:$mimeType;base64,$imageBase64"
        val requestJson = com.google.gson.JsonObject().apply {
            addProperty("model", model)
            addProperty("temperature", 0.2)
            add("messages", com.google.gson.JsonArray().apply {
                add(com.google.gson.JsonObject().apply {
                    addProperty("role", "system")
                    addProperty("content", systemPrompt)
                })
                add(com.google.gson.JsonObject().apply {
                    addProperty("role", "user")
                    add("content", com.google.gson.JsonArray().apply {
                        add(com.google.gson.JsonObject().apply {
                            addProperty("type", "image_url")
                            add("image_url", com.google.gson.JsonObject().apply { addProperty("url", dataUrl) })
                        })
                        add(com.google.gson.JsonObject().apply {
                            addProperty("type", "text")
                            addProperty(
                                "text",
                                if (isMultiMode) {
                                    "这是一张手机屏幕截图。请忽略界面装饰，只提取真实交易信息，并直接返回 {\"bills\":[...]}。"
                                } else {
                                    "这是一张手机屏幕截图。请忽略界面装饰，只提取最明确的一条真实交易，并直接返回单个 JSON 对象。"
                                }
                            )
                        })
                    })
                })
            })
        }

        return try {
            val response = getApi(ctx).chatRaw("Bearer $apiKey", requestJson)
            val content = response.choices.first().message.content
            Logger.d(ctx, "AIService", "Screen accounting multimodal response: $content")
            val result = parseAnalyzeResult(content, isMultiMode)

            // 分类合法性后处理：把 AI 返回的非法分类 fallback 到本地分类库
            result?.let { root ->
                val expenseCats = promptContext.expenseCats
                val incomeCats  = promptContext.incomeCats
                val assetNames: List<String> = promptContext.assetInfoList.mapNotNull { item -> item["name"]?.takeIf(String::isNotBlank) }
                fun fixBill(b: JSONObject) {
                    val rawType = b.optInt("type", 0)
                    val type = normalizeBillType(rawType)
                    b.put("type", type)
                    if (type == 2) {
                        if (rawType == 3 || b.optInt("subType", 0) == Bill.SUBTYPE_REPAYMENT || b.optString("category_name") == "还款") {
                            b.put("subType", Bill.SUBTYPE_REPAYMENT)
                            b.put("category_name", "还款")
                        } else if (b.optString("category_name").isBlank()) {
                            b.put("category_name", "转账")
                        }
                        return
                    }
                    // 分类合法性
                    val candidates = if (type == 1) incomeCats else expenseCats
                    val rawCate = b.optString("category_name", "")
                    val normalized = rawCate.replace(" > ", "/::/").replace(" - ", "/::/").replace(" / ", "/::/").trim()
                    val matched = findBestMatch(normalized, candidates)
                    if (matched != null) b.put("category_name", matched)
                    else if (normalized.isNotEmpty()) {
                        val fallback = resolveOtherCategory(candidates)
                        if (!fallback.isNullOrBlank()) b.put("category_name", fallback)
                        else b.put("category_name", "")
                    }
                    // 资产合法性：asset_name/to_asset_name 必须在资产库中存在
                    val rawAsset = b.optString("asset_name", "")
                    if (rawAsset.isNotBlank() && !assetNames.any { it.equals(rawAsset, ignoreCase = true) }) {
                        Logger.d(ctx, "AIService", "Screen: invalid asset_name='$rawAsset', clearing")
                        b.put("asset_name", "")
                    }
                    val rawToAsset = b.optString("to_asset_name", "")
                    if (rawToAsset.isNotBlank() && !assetNames.any { it.equals(rawToAsset, ignoreCase = true) }) {
                        b.put("to_asset_name", "")
                    }
                }
                if (root.has("bills")) {
                    val arr = root.getJSONArray("bills")
                    for (i in 0 until arr.length()) fixBill(arr.getJSONObject(i))
                } else if (root.has("amount")) {
                    fixBill(root)
                }
                normalizeMisplacedAssetOnExpenseOrIncome(root, assetNames)
                enforceTransferRequiresValidAssets(root, assetNames, expenseCats)
                if (!promptContext.assetFeatureEnabled) enforceNoAssetMode(root)
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
                return com.google.gson.JsonObject().apply {
                    addProperty("model", model)
                    addProperty("temperature", 0.1)
                    add("messages", com.google.gson.JsonArray().apply {
                        add(com.google.gson.JsonObject().apply { addProperty("role", "system"); addProperty("content", systemPrompt) })
                        add(com.google.gson.JsonObject().apply {
                            addProperty("role", "user")
                            addProperty("content", "请执行「小票结构化提取」并严格只返回一个 JSON 对象。\nJSON 结构：\n{\n  \"currency\": \"PLN\",\n  \"items\": [\n    {\"name\":\"商品名\",\"price\":5.89,\"currency\":\"PLN\"}\n  ]\n}\n\n约束：\n1. 只保留同时具备「商品名 + 实付金额」的商品行。\n2. 不要输出总计行、税率行、NIP、日期时间、店铺编号、Discount 等非商品行。\n3. OCR 重复行只保留一条，不能重复计数。\n4. 价格必须来自 OCR 原文，禁止臆造。\n5. 若存在\"数量 x 单价 金额\"结构，优先使用该结构确定数量和最终实付金额。\n6. 如果遇到 Opust/Discount，应使用折后金额（净额）作为该商品金额。\n\n${localHintBlock}以下是 OCR 文本：\n$cleanedOcrText")
                        })
                    })
                }
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

        val requestJson = com.google.gson.JsonObject().apply {
            addProperty("model", model)
            addProperty("temperature", 0.1)
            add("messages", com.google.gson.JsonArray().apply {
                add(com.google.gson.JsonObject().apply { addProperty("role", "system"); addProperty("content", systemPrompt) })
                add(com.google.gson.JsonObject().apply {
                    addProperty("role", "user")
                    addProperty("content", "本地OCR已提取清单（金额可信）：\n$localSummary\n\n原始OCR（仅用于校对，不允许引入新金额）：\n$cleanedOcrText")
                })
            })
        }

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

    suspend fun generateAccountingAssistantReply(
        ctx: Context,
        userInput: String,
        billSummary: String = "",
        extractorReplyHint: String = ""
    ): String {
        val apiKey = Prefs.getAiKey(ctx)
        if (apiKey.isEmpty()) throw IllegalArgumentException("请先在设置中配置 API Key")

        val model = Prefs.getAiChatModel(ctx).ifBlank { Prefs.getAiSingleModel(ctx) }
        val scene = if (billSummary.isBlank()) "NO_BILL" else "BILL_SAVED"
        val safeUserInput = shortenForModel(userInput, MAX_ASSISTANT_INPUT_CHARS)
        val safeBillSummary = shortenForModel(billSummary, MAX_ASSISTANT_SUMMARY_CHARS)
        val safeReplyHint = shortenForModel(extractorReplyHint, MAX_ASSISTANT_INPUT_CHARS, preserveTail = false)
        val styleInstruction = buildAssistantStyleInstruction(ctx)
        val userPrompt = buildString {
            appendLine("场景：$scene")
            appendLine("用户原话：$safeUserInput")
            if (safeBillSummary.isNotBlank()) appendLine("账单摘要：$safeBillSummary")
            if (safeReplyHint.isNotBlank()) appendLine("上游识别备注：$safeReplyHint")
            appendLine(styleInstruction)
            append("请直接回复用户。")
        }

        val requestJson = com.google.gson.JsonObject().apply {
            addProperty("model", model)
            addProperty("temperature", 0.7)
            add("messages", com.google.gson.JsonArray().apply {
                add(com.google.gson.JsonObject().apply {
                    addProperty("role", "system")
                    addProperty("content", CHAT_ASSISTANT_PROMPT_DEFAULT)
                })
                add(com.google.gson.JsonObject().apply {
                    addProperty("role", "user")
                    addProperty("content", userPrompt)
                })
            })
        }
        val response = getApi(ctx).chatRaw("Bearer $apiKey", requestJson)
        return response.choices.firstOrNull()?.message?.content?.trim().orEmpty()
    }

    suspend fun streamAccountingAssistantReply(
        ctx: Context,
        userInput: String,
        billSummary: String = "",
        extractorReplyHint: String = "",
        onDelta: (String) -> Unit
    ): Boolean {
        val apiKey = Prefs.getAiKey(ctx)
        if (apiKey.isEmpty()) throw IllegalArgumentException("请先在设置中配置 API Key")

        val model = Prefs.getAiChatModel(ctx).ifBlank { Prefs.getAiSingleModel(ctx) }
        val scene = if (billSummary.isBlank()) "NO_BILL" else "BILL_SAVED"
        val safeUserInput = shortenForModel(userInput, MAX_ASSISTANT_INPUT_CHARS)
        val safeBillSummary = shortenForModel(billSummary, MAX_ASSISTANT_SUMMARY_CHARS)
        val safeReplyHint = shortenForModel(extractorReplyHint, MAX_ASSISTANT_INPUT_CHARS, preserveTail = false)
        val styleInstruction = buildAssistantStyleInstruction(ctx)
        val userPrompt = buildString {
            appendLine("场景：$scene")
            appendLine("用户原话：$safeUserInput")
            if (safeBillSummary.isNotBlank()) appendLine("账单摘要：$safeBillSummary")
            if (safeReplyHint.isNotBlank()) appendLine("上游识别备注：$safeReplyHint")
            appendLine(styleInstruction)
            append("请直接回复用户。")
        }

        val requestJson = com.google.gson.JsonObject().apply {
            addProperty("model", model)
            addProperty("temperature", 0.7)
            addProperty("stream", true)
            add("messages", com.google.gson.JsonArray().apply {
                add(com.google.gson.JsonObject().apply {
                    addProperty("role", "system")
                    addProperty("content", CHAT_ASSISTANT_PROMPT_DEFAULT)
                })
                add(com.google.gson.JsonObject().apply {
                    addProperty("role", "user")
                    addProperty("content", userPrompt)
                })
            })
        }

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
            val requestJson = com.google.gson.JsonObject().apply {
                addProperty("model", model)
                addProperty("temperature", 0.1)
                add("messages", com.google.gson.JsonArray().apply {
                    add(com.google.gson.JsonObject().apply {
                        addProperty("role", "user")
                        add("content", com.google.gson.JsonArray().apply {
                            add(com.google.gson.JsonObject().apply {
                                addProperty("type", "text")
                                addProperty("text", "Reply with OK only.")
                            })
                            add(com.google.gson.JsonObject().apply {
                                addProperty("type", "input_audio")
                                add("input_audio", com.google.gson.JsonObject().apply {
                                    addProperty("data", buildProbeAudioBase64())
                                    addProperty("format", "wav")
                                })
                            })
                        })
                    })
                })
            }
            val response = getApi(ctx).chatRaw("Bearer $apiKey", requestJson)
            response.choices.firstOrNull()?.message?.content != null
        }.getOrElse { false }
    }

    private fun buildProbeAudioBase64(): String {
        val wavBytes = byteArrayOf(
            82,73,70,70,40,0,0,0,87,65,86,69,102,109,116,32,
            16,0,0,0,1,0,1,0,-128,62,0,0,0,125,0,0,2,0,16,0,
            100,97,116,97,4,0,0,0,0,0,0,0
        )
        return Base64.encodeToString(wavBytes, Base64.NO_WRAP)
    }

    private fun buildProbeImageBase64(ctx: Context): String {
        val bytes = ctx.resources.openRawResource(R.drawable.ic_screenshot).use { it.readBytes() }
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    private fun buildAssistantStyleInstruction(ctx: Context): String {
        val customPrompt = shortenForModel(
            Prefs.getAiChatReplyStyleCustomPrompt(ctx).trim(),
            800,
            preserveTail = false
        )
        return when (Prefs.getAiChatReplyStyle(ctx)) {
            "gentle" ->
                "回复风格：温柔、轻声、像陪伴一样。请直接对用户说人话，不要输出场景标签、英文状态词或说明文字。"
            "concise" ->
                "回复风格：简洁、克制、少废话，但仍然要像正常聊天回复。请直接对用户说完整的人话，不要只输出 BILL_SAVED、NO_BILL、已记录 这类内部标签。"
            "playful" ->
                "回复风格：活泼、俏皮、可以碎碎念一点。请直接对用户说人话，不要输出场景标签、英文状态词或说明文字。"
            "custom" ->
                if (customPrompt.isNotBlank()) {
                    "回复风格（用户自定义，高优先）：$customPrompt\n请直接对用户说自然的人话，不要输出场景标签、英文状态词、JSON 或内部指令。"
                } else {
                    DEFAULT_CUSTOM_REPLY_STYLE_GUIDE
                }
            else ->
                "回复风格：自然、可爱一点、可以带少量颜文字和俏皮话，但不要太吵。请直接对用户说人话，不要输出场景标签、英文状态词或说明文字。"
        }
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
            JSONObject(cleaned)
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

    private fun buildPromptCorrectionBlock(matchedRules: List<DbAiRule>, includeCategory: Boolean = true): String {
        if (matchedRules.isEmpty()) return ""
        val ruleStrings = matchedRules.joinToString("\n") { rule ->
            val sb = java.lang.StringBuilder("- 遇到关键词\"${rule.keyword}\"时：")
            rule.targetType?.let { type ->
                if (type in 0..3) {
                    val typeStr = when (type) { 0 -> "支出"; 1 -> "收入"; 2 -> "转账"; else -> "还款" }
                    sb.append(" 强制 type=${if (type == 3) 2 else type}($typeStr)${if (type == 3) "，subType=1" else ""}；")
                }
            }
            if (includeCategory && !rule.targetCategory.isNullOrEmpty()) sb.append(" 强制 category_name=\"${rule.targetCategory}\"；")
            if (!rule.targetAccount1.isNullOrEmpty()) sb.append(" 强制 asset_name=\"${rule.targetAccount1}\"；")
            if (!rule.targetAccount2.isNullOrEmpty()) sb.append(" 强制 to_asset_name=\"${rule.targetAccount2}\"；")
            sb.toString()
        }
        return "\n\n【专属习惯（高优先）】当输入满足以下条件时，必须采用：\n$ruleStrings"
    }

    private fun buildCategoryHierarchyHint(candidates: List<String>): String {
        if (candidates.isEmpty()) return "[]"
        val grouped = linkedMapOf<String, MutableList<String>>()
        candidates.forEach { category ->
            if (category.contains("/::/")) {
                val parent = category.substringBefore("/::/")
                val child = category.substringAfter("/::/")
                grouped.getOrPut(parent) { mutableListOf() }.add(child)
            } else {
                grouped.getOrPut(category) { mutableListOf() }
            }
        }
        return grouped.entries.joinToString("；") { (parent, children) ->
            if (children.isEmpty()) parent else "$parent -> ${children.joinToString("、")}"
        }
    }

    private suspend fun refineMultiBillCategories(
        ctx: Context,
        root: JSONObject,
        expenseCats: List<String>,
        incomeCats: List<String>,
        currentTimeStr: String,
        assetInfoList: List<Map<String, String>>,
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
            } else {
                bill.put("category_name", "")
                unresolvedIndexes += i
                unresolvedRemarks += remark
                unresolvedCandidates += candidates
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
                        currentTimeStr = currentTimeStr,
                        assetInfoList = assetInfoList,
                        matchedRules = matchedRules
                    )
                }.getOrNull()
                bill.put("category_name", refined ?: "")
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
        currentTimeStr: String,
        assetInfoList: List<Map<String, String>>,
        matchedRules: List<DbAiRule>
    ): String? {
        val apiKey = Prefs.getAiKey(ctx)
        if (apiKey.isEmpty()) return null

        val model = Prefs.getAiSingleModel(ctx).ifBlank { Prefs.getAiMultiModel(ctx) }
        val systemPrompt = buildString {
            appendLine("你是账单分类纠错助手。")
            appendLine("任务：只根据当前这一条账单的 remarks 来判断最合适的 category_name。")
            appendLine("要求：")
            appendLine("1. 只能处理当前这一条账单，不能受其他账单影响。")
            appendLine("2. 默认优先返回一级分类，不要主动追求过细分类。")
            appendLine("2.1 只有在 remarks 本身已经非常明确，或者命中了本地规则，才允许返回具体二级分类。")
            appendLine("2.2 只要对多个二级分类存在明显歧义，就停在一级分类，不要猜二级。")
            appendLine("3. 忽略第一阶段可能给出的临时分类候选，不要被上一轮结果锚定；只依据当前 remarks 本身重新判断。")
            appendLine("4. 只根据 remarks 本身和可选分类语义做判断，不要依赖排序、位置或习惯性猜测。")
            appendLine("5. 只输出 JSON，不要解释。")
            appendLine("6. 输出格式固定为：{\"category_name\":\"一级/::/二级\"}")
            appendLine("当前时间：$currentTimeStr")
            appendLine("资产库：${Gson().toJson(assetInfoList)}")
            appendLine("账单类型：${if (type == 1) "收入" else "支出"}")
            appendLine("可选分类：${Gson().toJson(candidates)}")
            appendLine("分类层级参考：${buildCategoryHierarchyHint(candidates)}")
            if (matchedRules.isNotEmpty()) {
                append(buildPromptCorrectionBlock(matchedRules))
                appendLine()
            }
        }

        val userPrompt = buildString {
            appendLine("当前账单 remarks：$remark")
            append("请返回这条账单最终最合适的 category_name。")
        }

        val requestJson = com.google.gson.JsonObject().apply {
            addProperty("model", model)
            addProperty("temperature", 0.1)
            add("messages", com.google.gson.JsonArray().apply {
                add(com.google.gson.JsonObject().apply {
                    addProperty("role", "system")
                    addProperty("content", systemPrompt)
                })
                add(com.google.gson.JsonObject().apply {
                    addProperty("role", "user")
                    addProperty("content", userPrompt)
                })
            })
        }

        val response = getApi(ctx).chatRaw("Bearer $apiKey", requestJson)
        val content = response.choices.firstOrNull()?.message?.content.orEmpty()
        val parsed = parseAnalyzeResult(content, isMultiMode = false) ?: return null
        val rawCate = parsed.optString("category_name", "")
        val normalized = rawCate.replace(" > ", "/::/").replace(" - ", "/::/").replace(" / ", "/::/").trim()
        return findBestMatch(normalized, candidates)
            ?: candidates.find { it.contains("其他") && normalized.isNotBlank() }
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

    private fun resolveLocalRulePrefill(matchedRules: List<DbAiRule>): LocalRulePrefill? {
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
        return LocalRulePrefill(type, category, assetName, toAssetName)
    }

    private fun applyLocalPrefillToResult(root: JSONObject, prefill: LocalRulePrefill): LocalRuleApplyResult {
        val changedFields = linkedSetOf<String>()
        val correctedFields = linkedSetOf<String>()
        prefill.type?.let { t ->
            val hadValue = root.has("type")
            val oldValue = root.optInt("type", Int.MIN_VALUE)
            if (!hadValue || oldValue != t) {
                changedFields += "type"
                if (hadValue && oldValue != t) {
                    correctedFields += "type"
                }
            }
            root.put("type", t)
            if (t == 2 && prefill.category == "还款") {
                val hadSubType = root.has("subType")
                val oldSubType = root.optInt("subType", Int.MIN_VALUE)
                if (!hadSubType || oldSubType != 1) {
                    changedFields += "subType"
                    if (hadSubType && oldSubType != 1) {
                        correctedFields += "subType"
                    }
                }
                root.put("subType", 1)
            }
        }
        prefill.category?.takeIf { it.isNotBlank() }?.let {
            val old = root.optString("category_name", "")
            if (!root.has("category_name") || old != it) {
                changedFields += "category_name"
                if (old.isNotBlank() && old != it) {
                    correctedFields += "category_name"
                }
            }
            root.put("category_name", it)
        }
        prefill.assetName?.takeIf { it.isNotBlank() }?.let {
            val old = root.optString("asset_name", "")
            if (!root.has("asset_name") || old != it) {
                changedFields += "asset_name"
                if (old.isNotBlank() && old != it) {
                    correctedFields += "asset_name"
                }
            }
            root.put("asset_name", it)
        }
        prefill.toAssetName?.takeIf { it.isNotBlank() }?.let {
            val old = root.optString("to_asset_name", "")
            if (!root.has("to_asset_name") || old != it) {
                changedFields += "to_asset_name"
                if (old.isNotBlank() && old != it) {
                    correctedFields += "to_asset_name"
                }
            }
            root.put("to_asset_name", it)
        }
        return LocalRuleApplyResult(
            applied = changedFields.isNotEmpty(),
            corrected = correctedFields.isNotEmpty(),
            correctedFields = correctedFields.toList(),
            changedFields = changedFields.toList()
        )
    }

    private fun summarizeLocalRuleSensitiveFields(json: JSONObject): String {
        val type = if (json.has("type")) json.optInt("type", -1).toString() else "null"
        val subType = if (json.has("subType")) json.optInt("subType", -1).toString() else "null"
        val category = json.optString("category_name", "")
        val asset = json.optString("asset_name", "")
        val toAsset = json.optString("to_asset_name", "")
        return "type=$type,subType=$subType,category=$category,asset=$asset,toAsset=$toAsset"
    }

    private fun findBestMatch(input: String, candidates: List<String>): String? {
        if (input.isEmpty()) return null
        if (candidates.contains(input)) return input
        val normalizedInput = input.replace(" ", "")
        candidates.find { it.substringAfterLast("/::/").replace(" ", "") == normalizedInput }?.let { return it }
        candidates.find { it.replace(" ", "") == normalizedInput }?.let { return it }
        candidates.find { !it.contains("/::/") && normalizedInput.startsWith(it.replace(" ", "") + "/::/") }?.let { return it }
        return null
    }

    private fun buildRemarksRichnessRule(): String =
        "\n【remarks 精简（高优先）】remarks 只保留该笔交易的核心语义关键词，默认短文本。\n" +
        "- 建议长度：4~12 个字；尽量使用名词短语，不要写完整叙述句。\n" +
        "- 优先保留“事项 + 对象/场景”，如：\"午餐拉面\"、\"超市买菜\"、\"打车通勤\"。\n" +
        "- 禁止重复金额、币种、账户名（这些由其他字段表达）。\n"

    private fun buildIncomeCategoryHardRule(): String =
        "\n【收入分类硬约束】当 type=1（收入）时，category_name 必须从收入分类列表 {{INCOME_CATS}} 中原样选择。\n" +
        "- 禁止输出“收入”“入账”等泛词作为分类。\n" +
        "- 若无法判断具体收入分类，优先输出收入分类中的“其他/其它”类目；仍无法匹配时可留空。\n"

    private fun resolveOtherCategory(candidates: List<String>): String? =
        candidates.find { it.contains("其他") || it.contains("其它") }

    private fun normalizeBillType(rawType: Int): Int = when (rawType) {
        0, 1, 2 -> rawType
        3 -> 2
        else -> 0
    }

    /**
     * 仅当转出/转入都命中本地资产时，才允许保留转账；否则回退为支出。
     * 这可避免“给小王转200买菜”这类外部转款被误记为内部账户转账。
     */
    private fun normalizeMisplacedAssetOnExpenseOrIncome(
        root: JSONObject,
        assetNames: List<String>
    ) {
        if (assetNames.isEmpty()) return
        fun isKnownAsset(name: String): Boolean =
            assetNames.any { it.equals(name, ignoreCase = true) }

        fun normalize(json: JSONObject) {
            val type = normalizeBillType(json.optInt("type", 0))
            if (type != Bill.TYPE_EXPENSE && type != Bill.TYPE_INCOME) return
            val fromAsset = json.optString("asset_name", "").trim()
            val toAsset = json.optString("to_asset_name", "").trim()
            if (fromAsset.isNotBlank() || toAsset.isBlank()) return
            if (!isKnownAsset(toAsset)) return
            json.put("asset_name", toAsset)
            json.put("to_asset_name", "")
        }

        if (root.has("bills")) {
            val bills = root.getJSONArray("bills")
            for (i in 0 until bills.length()) {
                normalize(bills.getJSONObject(i))
            }
        } else if (root.has("amount")) {
            normalize(root)
        }
    }

    /**
     * 仅当转出/转入都命中本地资产时，才允许保留转账；否则回退为支出。
     * 这可避免“给小王转200买菜”这类外部转款被误记为内部账户转账。
     */
    private fun enforceTransferRequiresValidAssets(
        root: JSONObject,
        assetNames: List<String>,
        expenseCats: List<String>
    ) {
        if (assetNames.isEmpty()) return
        val fallbackExpenseCategory = resolveOtherCategory(expenseCats) ?: "其他"

        fun isKnownAsset(name: String): Boolean =
            assetNames.any { it.equals(name, ignoreCase = true) }

        fun normalize(json: JSONObject) {
            val type = normalizeBillType(json.optInt("type", 0))
            if (type != Bill.TYPE_TRANSFER) return

            val isRepayment =
                json.optInt("subType", 0) == Bill.SUBTYPE_REPAYMENT ||
                json.optString("category_name", "").trim() == "还款"
            if (isRepayment) return

            val fromAsset = json.optString("asset_name", "").trim()
            val toAsset = json.optString("to_asset_name", "").trim()
            val validTransfer = fromAsset.isNotBlank() && toAsset.isNotBlank() && isKnownAsset(fromAsset) && isKnownAsset(toAsset)
            if (validTransfer) return

            json.put("type", Bill.TYPE_EXPENSE)
            if (json.has("subType")) json.remove("subType")
            json.put("to_asset_name", "")
            val category = json.optString("category_name", "").trim()
            if (category.isBlank() || category == "转账") {
                json.put("category_name", fallbackExpenseCategory)
            }
        }

        if (root.has("bills")) {
            val bills = root.getJSONArray("bills")
            for (i in 0 until bills.length()) {
                normalize(bills.getJSONObject(i))
            }
        } else if (root.has("amount")) {
            normalize(root)
        }
    }

    private fun enforceNoAssetMode(root: JSONObject) {
        fun normalizeBill(json: JSONObject) {
            val normalizedType = if (json.optInt("type", 0) == 1) 1 else 0
            json.put("type", normalizedType)
            json.put("asset_name", "")
            json.put("to_asset_name", "")
            json.put("fee", 0.0)
            if (json.has("subType")) {
                json.remove("subType")
            }
        }

        if (root.has("bills")) {
            val bills = root.getJSONArray("bills")
            for (i in 0 until bills.length()) {
                normalizeBill(bills.getJSONObject(i))
            }
        } else if (root.has("amount")) {
            normalizeBill(root)
        }
    }

    private fun hasSecondLevelCategories(
        expenseCats: List<String>,
        incomeCats: List<String>
    ): Boolean {
        return expenseCats.any { it.contains("/::/") } || incomeCats.any { it.contains("/::/") }
    }

    private fun adaptPromptForCategoryDepth(prompt: String, hasSecondLevel: Boolean): String {
        val removableKeywords = listOf(
            "优先命中更细的子分类",
            "子分类格式固定为 一级/::/二级",
            "子分类格式必须为 一级/::/二级",
            "子分类格式必须输出 一级/::/二级",
            "一级/::/二级"
        )
        val normalized = prompt
            .lineSequence()
            .filterNot { line -> removableKeywords.any { key -> line.contains(key) } }
            .joinToString("\n")
            .trim()
        val rule = if (hasSecondLevel) {
            "\n【分类层级约束】当前分类库包含二级分类：优先命中更细的子分类；命中子分类时 category_name 必须输出“一级/::/二级”。\n"
        } else {
            "\n【分类层级约束】当前分类库没有二级分类，category_name 只能输出一级分类名；禁止输出“一级/::/二级”格式。\n"
        }
        return normalized + rule
    }

    private fun cleanJsonString(input: String): String {
        var s = input.trim()
        if (s.startsWith("```json")) s = s.removePrefix("```json")
        if (s.startsWith("```")) s = s.removePrefix("```")
        if (s.endsWith("```")) s = s.removeSuffix("```")
        return s.trim()
    }

    private fun buildRawRequest(request: ChatRequest): com.google.gson.JsonObject {
        val gson = com.google.gson.GsonBuilder()
            .registerTypeAdapter(MessageUnion::class.java, MessageUnionSerializer())
            .create()
        return gson.fromJson(gson.toJson(request), com.google.gson.JsonObject::class.java)
    }
}
