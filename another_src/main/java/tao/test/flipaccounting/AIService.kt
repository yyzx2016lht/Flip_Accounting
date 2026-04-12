package tao.test.flipaccounting

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonObject
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.ResponseBody
import org.json.JSONArray
import org.json.JSONObject
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

// --- 数据模型 ---
data class ChatRequest(
    val model: String,
    val messages: List<MessageUnion>,
    val temperature: Double = 0.3,
    val response_format: ResponseFormat? = ResponseFormat("json_object")
)

// 支持纯文本消息（旧有逻辑不变）
data class Message(val role: String, val content: String)

// 支持多模态消息（文本 + 图片）
data class MultimodalMessage(val role: String, val content: List<ContentPart>)
data class ContentPart(val type: String, val text: String? = null, val image_url: ImageUrl? = null)
data class ImageUrl(val url: String)

// 统一联合类型，由序列化时决定实际结构
sealed class MessageUnion {
    data class Text(val msg: Message) : MessageUnion()
    data class Multimodal(val msg: MultimodalMessage) : MessageUnion()
}

// 自定义 Gson 序列化，将 MessageUnion 正确序列化
class MessageUnionSerializer : com.google.gson.JsonSerializer<MessageUnion> {
    override fun serialize(src: MessageUnion, typeOfSrc: java.lang.reflect.Type, context: com.google.gson.JsonSerializationContext): com.google.gson.JsonElement {
        return when (src) {
            is MessageUnion.Text -> context.serialize(src.msg)
            is MessageUnion.Multimodal -> context.serialize(src.msg)
        }
    }
}

data class ResponseFormat(val type: String)
data class ChatResponse(val choices: List<Choice>)
data class Choice(val message: Message)
data class AudioResponse(val text: String)

data class ModelsResponse(val data: List<ModelItem>)
data class ModelItem(val id: String)

// --- API 接口 ---
interface SiliconFlowApi {
    @GET("v1/models")
    suspend fun getModels(@Header("Authorization") auth: String): ModelsResponse

    @POST("v1/chat/completions")
    suspend fun chat(@Header("Authorization") auth: String, @Body body: ChatRequest): ChatResponse

    @POST("v1/chat/completions")
    suspend fun chatRaw(@Header("Authorization") auth: String, @Body body: com.google.gson.JsonObject): ChatResponse

    @Multipart
    @POST("v1/audio/transcriptions")
    suspend fun transcribe(
        @Header("Authorization") auth: String,
        @Part model: MultipartBody.Part,
        @Part file: MultipartBody.Part
    ): AudioResponse
}

// --- 服务实现 ---
object AIService {

    const val DEFAULT_PROMPT = """你是一个严谨的账单分类引擎。你的核心任务是：**深度遍历**提供的分类库，找到与用户描述最匹配的“末级分类”。

【数据源】
1. 资产库: {{ASSETS}}
2. 支出分类: {{EXPENSE_CATS}}
3. 收入分类: {{INCOME_CATS}}
4. 参照时间: {{TIME}}

【必须执行的匹配逻辑 (优先级从高到低)】

1.  **子分类优先原则 (Deep Search Strategy)**:
    * **规则**: 不要只看一级分类的名字！必须扫描所有一级分类下的 `subs` (子分类) 列表。
    * **案例**: 用户说“买菜”。
        * 错误逻辑：看一级分类 -> "吃的"？好像不对 -> 归类为"其他"。
        * 正确逻辑：扫描一级分类 "吃的" 的子项 -> 发现 "买菜" -> **完美匹配** -> 输出 `吃的/::/买菜`。

2.  **语义映射机制**:
    * **包含匹配**: 如果用户描述的词（如“买菜”）直接包含在分类名称中，直接选中。
    * **场景推断**:
        * "买菜"、"超市买肉" -> 映射为 **`吃的/::/买菜`**。
        * "发红包" -> 映射为 **`发红包`** (该项无子分类，直接输出)。
        * "打车" -> 映射为 **`交通/::/打车`**。
        * "加油" -> 映射为 **`汽车/::/油费`**。

3.  **资产匹配**:
    * **模糊匹配**: "建行" = "建设银行"；"农行" = "农业银行"。
    * **默认推断**: 用户提到微信或者支付宝等软件名字，如果在资产中见到则代表需要选择该资产。

4.  **交易类型 (Type)**:
    * **0 (支出)**: 买、付、消费、其他花销、发红包。
    * **1 (收入)**: 收、退款、入账、收入、赚钱。
    * **2 (转账)**: 仅限 **自己** 的资产互转（如 支付宝->银行卡，或者银行卡->银行卡）。
    * **3 (还款)**: 还信用卡/还花呗/还白条/还月付等常用的信用资产。

【输出数据格式】
* **Time**: 统一转换为 `yyyy-MM-dd HH:mm:ss`。
* **Category Name**: 
    * 若命中子分类，必须输出全路径：`一级分类名/::/二级分类名`。
    * 若命中一级分类且无子分类，输出：`一级分类名`。
* **格式**: 仅输出一个 JSON 对象，**严禁**输出 Markdown 代码块标记（```json），严禁输出解释语。

【Few-Shot 强校验示例】

输入: "刚刚买了点菜30块，此时时间是2026年2月18日"
逻辑: 
1. 扫描所有子分类 -> 在 "吃的" 下发现 "买菜"。
2. 路径确认为 `吃的/::/买菜`。
输出: 
{"amount":30.0, "type":0, "asset_name":"", "category_name":"吃的/::/买菜", "time":"2026-02-18 21:10:00", "remarks":"买菜", "currency":"CNY", "to_asset_name":"", "fee":0.0}

输入: "给小鲁发红包200，微信，2026年2月18日"
逻辑:
1. 扫描子分类 -> 无匹配。
2. 扫描一级分类 -> 发现 "发红包"。
输出:
{"amount":200.0, "type":0, "asset_name":"微信", "category_name":"发红包", "time":"2026-02-18 21:12:00", "remarks":"给小鲁发红包", "currency":"CNY", "to_asset_name":"", "fee":0.0}"""

    const val MULTI_BILL_PROMPT = """### 【角色定义】
你是一个高精度的账单解析与分类引擎。你的任务是将用户的一段自然语言描述（包含单笔或多笔交易），解析为结构化的账单 JSON 数组。

### 【核心资源】
1. 资产库: {{ASSETS}}
2. 支出分类: {{EXPENSE_CATS}}
3. 收入分类: {{INCOME_CATS}}
4. 基准时间: {{TIME}}

### 【必须执行的解析逻辑 (优先级排序)】

#### 1. 语义断句与实体绑定 (Segmentation & Binding)
* **断句**: 识别连接词（“然后”、“还有”、“再”）、标点或金额关键词，将长句拆分为独立事件。
* **绑定**: 严格确保“金额-动作-资产”的对应关系。如果一句话提到两个金额（如“吃饭20买水3”），必须拆分为两条记录。

#### 2. 深度分类与映射 (Deep Category Mapping)
* **子分类优先**: 必须遍历 `subs` 列表。若命中子分类，输出 `一级分类名/::/二级分类名`；若仅命中一级且无子分类，输出 `一级分类名`。
* **强规则逻辑 (纠偏)**:
    * **主食规则**: 出现“面、饭、粥、粉、饺子、馄饨、便当”等，强制归类为：`吃的/::/三餐`，遇到其他可以作为主食的也归类为三餐即可。
    * **食材规则**: 出现“买菜、肉、禽、蛋、生鲜”等，强制归类为：`吃的/::/买菜`。
    * **场景推断**: “打车/滴滴” -> `交通/::/打车`；“加油” -> `汽车/::/油费`；“发红包” -> `发红包`;"充话费"->'网络通讯费用'、根据具体情况具体分析。

#### 3. 上下文继承机制 (Inheritance Strategy)
* **资产继承**: 若后续事件未提及资产，默认继承前一事件的资产。若全篇未提及，留空（除非符合“扫码”默认为微信/支付宝的逻辑）。
* **时间微调**: 若无明确时间，默认使用基准时间。多笔账单同时发生时，建议在秒级做 `+1s` 递增处理以示区分。

4.  **交易类型 (Type)**:
    * **0 (支出)**: 买、付、消费、其他花销、发红包。
    * **1 (收入)**: 收、退款、入账、收入、赚钱。
    * **2 (转账)**: 仅限 **自己** 的资产互转（如 支付宝->银行卡，或者银行卡->银行卡）。
    * **3 (还款)**: 还信用卡/还花呗/还白条/还月付等常用的信用资产。
    
### 【输出约束】
* **格式**: 必须输出一个包含 `bills` 键名的 JSON 对象。
* **严禁**: 严禁输出 Markdown 代码块标记（```json），严禁输出任何解释性文字。
* **货币规则**: 从输入文本中识别货币（PLN/EUR/USD 等），每条账单的 `currency` 字段必须与输入中提到的货币一致；同一段输入若只提到一种货币，所有账单均使用该货币；未提及货币时默认 "CNY"。
* **字段定义**: `amount` (float), `type` (int), `asset_name` (string), `category_name` (string), `time` (yyyy-MM-dd HH:mm:ss), `remarks` (string), `currency` (从输入中识别货币代码，如 "PLN"/"EUR"/"USD"，默认 "CNY"), `to_asset_name` (string), `fee` (float).

### 【Few-Shot 示例】

**输入**: "刚才吃了一碗牛肉面20，又买了一瓶水3块，都是微信付的"
**逻辑**: [事件1: 牛肉面20] -> 命中主食规则 -> `吃的/::/三餐`；[事件2: 水3] -> 继承资产“微信”。
**输出**:
{
  "bills": [
    {
      "amount": 20.0,
      "type": 0,
      "asset_name": "微信",
      "category_name": "吃的/::/三餐",
      "time": "2026-03-19 12:00:00",
      "remarks": "吃牛肉面",
      "currency": "CNY",
      "to_asset_name": "",
      "fee": 0.0
    },
    {
      "amount": 3.0,
      "type": 0,
      "asset_name": "微信",
      "category_name": "喝的/::/纯净水",
      "time": "2026-03-19 12:00:01",
      "remarks": "买水",
      "currency": "CNY",
      "to_asset_name": "",
      "fee": 0.0
    }
  ]
}
**输入**: "刚才买了个苹果4PLN，又买了一个洋葱2PLN，用的是visa卡"
**逻辑**: [事件1: 买苹果] -> 命中水果规则 -> `吃的/::/水果`；[事件2: 洋葱]-> 命中买菜规则 -> `吃的/::/买菜` -> 继承资产“visa卡”，货币均为'PLN'。
**输出**:
{
  "bills": [
    {
      "amount": 4.0,
      "type": 0,
      "asset_name": "visa卡",
      "category_name": "吃的/::/水果",
      "time": "2026-03-19 12:00:00",
      "remarks": "买了个苹果",
      "currency": "PLN",
      "to_asset_name": "",
      "fee": 0.0
    },
    {
      "amount": 2.0,
      "type": 0,
      "asset_name": "visa卡",
      "category_name": "吃的/::/买菜",
      "time": "2026-03-19 12:00:01",
      "remarks": "买了个洋葱",
      "currency": "PLN",
      "to_asset_name": "",
      "fee": 0.0
    }
  ]
}
**输入**: "建行转账给招行5000"
**逻辑**: [转账事件] -> 匹配资产全称 -> type 设为 2。
**输出**:
{
  "bills": [
    {
      "amount": 5000.0,
      "type": 2,
      "asset_name": "建设银行",
      "category_name": "转账",
      "time": "2026-03-19 12:05:00",
      "remarks": "建行转账给招行",
      "currency": "CNY",
      "to_asset_name": "招商银行",
      "fee": 0.0
    }
  ]
}"""

    /**
     * 中文小票专用提示词（超市/电商/外卖等国内场景）
     */
    const val RECEIPT_BILL_PROMPT_CN = """【角色】你是专业的购物小票解析助手。你的任务是将OCR识别到的或图片中的小票内容，整理成易读的中文自然语言清单。

【任务要求】
1. **提取与翻译**：识别小票上的每一个商品条目。如果是外文，请翻译成中文名称（可保留原文在括号内）。
2. **格式统一**：请严格按照以下自然语言格式输出每一行：
   购买[商品名称]花了[金额] [币种]
3. **汇总**：最后一行输出总金额：
   总计花费为 [总金额] [币种]。
4. **排除无关信息**：不要输出税号、地址、电话、会员卡号等无关信息。不要输出 JSON 代码块。只输出纯文本清单。

【示例输出】
购买洋葱 花了0.67 元
购买燕麦棒花了8.98 元
购买A4笔记本花了5.99元
"""

    /**
     * 外语小票专用提示词（波兰/英语/德语等欧洲超市）
     */
    const val RECEIPT_BILL_PROMPT_FOREIGN = """【角色】你是外语购物小票解析专家。你的任务是将OCR识别到的或图片中的外语小票内容，整理成易读的中文自然语言清单。

【核心规则】
1. **精准翻译**：必须将外文商品名翻译为中文（并在括号内保留原文）。
   - 翻译参考（波兰语示例）：
     * "Mleko" → 牛奶/乳制品
     * "Banan" → 香蕉
     * "Chleb" → 面包
     * "Ser" → 奶酪
     * "Kurczak" → 鸡肉
     * "Jogurt" → 酸奶
     * "Woda" → 水
     * "Pomidory" → 西红柿
     * "Szampon" → 洗发水
     * "Detergent" → 清洁剂
     * "Papier Toaletowy" → 卫生纸

2. **保留币种**：
   - 遇到 "PLN", "zł" → 保留 PLN
   - 遇到 "EUR", "€" → 保留 EUR
   - 不要转换为 CNY，除非原币种就是 CNY。

3. **输出格式（严格执行）**：
   购买[中文商品名] ([原文商品名])花了[金额] [币种]
   ...
   总计花费为 [总金额] [币种]。

4. **排除干扰**：
   - 不输出税额、地址、电话、会员卡号。
   - 不输出 JSON 代码块。只输出纯文本。

【示例输出】
购买洋葱 (Cebula)花了0.67 PLN
购买燕麦棒 (Granola GO)花了8.98 PLN
购买A4笔记本 (Zeszyt A4)花了5.99 PLN
总计花费为 74.69 PLN。
"""

    /**
     * 通用小票提示词（兼容模式，向后兼容旧存储）
     * 保留此字段供 AiConfigActivity 中自定义编辑时展示默认值
     */
    const val RECEIPT_BILL_PROMPT = RECEIPT_BILL_PROMPT_CN


    // OCR模式常量
    const val OCR_MODE_LOCAL = 0   // 本地ML Kit OCR + 文本AI
    const val OCR_MODE_MULTIMODAL = 1 // 直接多模态AI（发送图片）

    const val MULTI_BILL_PROMPT_CONCISE = """你是账单解析助手。请把用户输入解析成 JSON：{"bills":[...]}，不要输出解释或代码块。

可用资源：
1. 资产：{{ASSETS}}
2. 支出分类：{{EXPENSE_CATS}}
3. 收入分类：{{INCOME_CATS}}
4. 基准时间：{{TIME}}

规则：
1. 一句话里有多笔交易时，按金额和动作拆成多条账单。
2. `type`：0=支出，1=收入，2=转账，3=还款。
3. 出现“买、购买、花了、支付、刷卡、visa、mastercard、总计花费、小票、receipt、discount”等购物语义时，默认判为支出，不得判为收入。
4. 只有明确出现“工资、收入、收款、收到转账、退款到账、报销到账”等收款语义时，才判为收入。
5. 分类时优先按商品或服务本身的属性，去分类库里找最贴近的末级分类，不要用“都能吃”“都算日用”这种宽泛逻辑。
6. 如果无法精确命中末级分类，再退到更贴近的上一级或“其他”，不要硬套到明显不合适的分类。
7. 资产名尽量匹配用户输入中的 Visa、微信、支付宝、银行卡等；匹配不到可留空。
8. 时间统一输出 yyyy-MM-dd HH:mm:ss；同一段里的多笔可按秒递增。

输出字段：
amount, type, asset_name, category_name, time, remarks, currency, to_asset_name, fee
"""

    private fun getApi(ctx: Context): SiliconFlowApi {
        var baseUrl = Prefs.getAiUrl(ctx)
        if (baseUrl.isEmpty()) {
            baseUrl = "https://api.siliconflow.cn/"
        }
        if (!baseUrl.endsWith("/")) {
            baseUrl += "/"
        }
        
        val client = okhttp3.OkHttpClient.Builder()
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

    /**
     * 语音转文字：使用 SenseVoiceSmall 模型
     */
    suspend fun speechToText(ctx: Context, audioFile: File): String? {
        val apiKey = Prefs.getAiKey(ctx)
        if (apiKey.isEmpty()) return null

        return try {
            val requestFile = okhttp3.RequestBody.create("audio/mpeg".toMediaTypeOrNull(), audioFile)
            val filePart = okhttp3.MultipartBody.Part.createFormData("file", audioFile.name, requestFile)
            val modelPart = okhttp3.MultipartBody.Part.createFormData("model", "FunAudioLLM/SenseVoiceSmall")

            val response = getApi(ctx).transcribe("Bearer $apiKey", modelPart, filePart)
            response.text
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * 分析记账文本
     */
    suspend fun analyzeAccounting(ctx: Context, userInput: String, isMultiModeOverride: Boolean? = null): JSONObject? {
        Logger.d(ctx, "AIService", "Analyzing: $userInput")
        val apiKey = Prefs.getAiKey(ctx)
        val isMultiMode = isMultiModeOverride ?: Prefs.isMultiBillEnabled(ctx)
        val model = if (isMultiMode) Prefs.getAiMultiModel(ctx) else Prefs.getAiSingleModel(ctx)
        if (apiKey.isEmpty()) {
            throw IllegalArgumentException("请先在设置中配置 API Key")
        }

        // 1. 准备数据
        val assets = Prefs.getAssets(ctx).map { it.name }
        val currencies = Prefs.getActiveCurrencies(ctx).toList()
        
        val expenseCats = mutableListOf<String>()
        Prefs.getCategories(ctx, Prefs.TYPE_EXPENSE).forEach { parentNode ->
            if (parentNode.subs.isEmpty()) {
                expenseCats.add(parentNode.name)
            } else {
                parentNode.subs.forEach { childNode ->
                    expenseCats.add("${parentNode.name}/::/${childNode.name}")
                }
            }
        }
        
        val incomeCats = mutableListOf<String>()
        Prefs.getCategories(ctx, Prefs.TYPE_INCOME).forEach { parentNode ->
            if (parentNode.subs.isEmpty()) {
                incomeCats.add(parentNode.name)
            } else {
                parentNode.subs.forEach { childNode ->
                    incomeCats.add("${parentNode.name}/::/${childNode.name}")
                }
            }
        }

        val demoAsset = assets.firstOrNull() ?: "微信"
        val demoExpenseCat = expenseCats.firstOrNull() ?: "吃的"
        val demoIncomeCat = incomeCats.firstOrNull() ?: "工资"

        val now = Date()
        val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val weekFormat = SimpleDateFormat("EEEE", Locale.getDefault())
        val currentTimeStr = "${timeFormat.format(now)} (${weekFormat.format(now)})"

        // 2. 构建 System Prompt
        var p = if (isMultiMode) Prefs.getMultiBillPrompt(ctx) else Prefs.getAiPrompt(ctx)
        if (p.isEmpty()) {
            p = if (isMultiMode) MULTI_BILL_PROMPT_CONCISE else DEFAULT_PROMPT
        }
        if (isMultiMode) {
            p += """

【购物小票摘要强约束】
如果输入里出现“购买”“花了”“总计花费”“刷卡”“支付”“visa”“mastercard”“receipt”“discount”等购物小票语义，
则这些账单一律视为支出，`type` 必须为 0，绝不能输出为收入。
只有在明确出现“工资”“收入”“收到转账”“退款到账”“报销到账”“收款”等语义时，才允许 `type` 为 1。
像“我前天买的，用visa卡支付”这种补充信息，仍然是在描述购物支出，不能改判成收入。

"""
        }
        
        // --- 阶段一：动态 Prompt 增强 (AI 纠错) ---
        if (Prefs.isAiPromptCorrectionEnabled(ctx)) {
            val matchedRules = Prefs.getAiRules(ctx).filter { rule ->
                rule.isEnabled && rule.keyword.split("\\s+".toRegex()).filter { it.isNotEmpty() }
                    .all { userInput.contains(it, ignoreCase = true) }
            }
            if (matchedRules.isNotEmpty()) {
                val ruleStrings = matchedRules.joinToString("\n") { rule ->
                    val sb = java.lang.StringBuilder("- 遇到关键字“${rule.keyword}”: ")
                    rule.targetType?.let { type ->
                        val typeStr = when(type) { 0 -> "支出"; 1 -> "收入"; 2 -> "转账"; 3 -> "还款"; else -> type.toString() }
                        sb.append("强制要求\"type\"=$type($typeStr); ")
                    }
                    if (!rule.targetCategory.isNullOrEmpty()) {
                        sb.append("强制要求\"category_name\"=\"${rule.targetCategory}\"; ")
                    }
                    if (!rule.targetAccount1.isNullOrEmpty()) {
                        sb.append("强制要求\"asset_name\"=\"${rule.targetAccount1}\"; ")
                    }
                    if (!rule.targetAccount2.isNullOrEmpty()) {
                        sb.append("强制要求\"to_asset_name\"=\"${rule.targetAccount2}\"; ")
                    }
                    sb.toString()
                }
                p += "\n\n【专属习惯(高优)】当输入满足以下条件时，必须采用:\n$ruleStrings"
            }
        }

        val systemPrompt = p.replace("{{TIME}}", currentTimeStr)
            .replace("{{ASSETS}}", Gson().toJson(assets))
            .replace("{{EXPENSE_CATS}}", Gson().toJson(expenseCats))
            .replace("{{INCOME_CATS}}", Gson().toJson(incomeCats))
            .replace("{{CURRENCIES}}", Gson().toJson(currencies))
            .replace("{{DEMO_ASSET}}", demoAsset)
            .replace("{{DEMO_EXPENSE_CAT}}", demoExpenseCat)
            .replace("{{DEMO_INCOME_CAT}}", demoIncomeCat)

        // 3. 发送请求
        return try {
            val requestJson = com.google.gson.JsonObject().apply {
                addProperty("model", model)
                addProperty("temperature", 0.3)
                add("response_format", com.google.gson.JsonObject().apply { addProperty("type", "json_object") })
                add("messages", com.google.gson.JsonArray().apply {
                    add(com.google.gson.JsonObject().apply {
                        addProperty("role", "system")
                        addProperty("content", systemPrompt)
                    })
                    add(com.google.gson.JsonObject().apply {
                        addProperty("role", "user")
                        addProperty("content", userInput)
                    })
                })
            }
            val response = getApi(ctx).chatRaw("Bearer $apiKey", requestJson)
            
            val content = response.choices.first().message.content
            Logger.d(ctx, "AIService", "AI Response: $content")
            
            // --- 阶段二：本地强匹配纠错 (本地强行覆盖) ---
            val finalContent = if (Prefs.isLocalRuleOverrideEnabled(ctx)) {
                val matchedRules = Prefs.getAiRules(ctx).filter { rule ->
                    rule.isEnabled && rule.keyword.split("\\s+".toRegex()).filter { it.isNotEmpty() }
                        .all { userInput.contains(it, ignoreCase = true) }
                }
                if (matchedRules.isNotEmpty()) {
                    try {
                        val element = com.google.gson.JsonParser.parseString(cleanJsonString(content))
                        if (element.isJsonObject) {
                            val json = element.asJsonObject
                            if (json.has("bills") && json.get("bills").isJsonArray) {
                                val arr = json.getAsJsonArray("bills")
                                arr.forEach { item ->
                                    if (item.isJsonObject) {
                                        val itemObj = item.asJsonObject
                                        matchedRules.forEach { rule ->
                                            rule.targetType?.let { itemObj.addProperty("type", it) }
                                            if (!rule.targetCategory.isNullOrEmpty()) itemObj.addProperty("category_name", rule.targetCategory)
                                            if (!rule.targetAccount1.isNullOrEmpty()) itemObj.addProperty("asset_name", rule.targetAccount1)
                                            if (!rule.targetAccount2.isNullOrEmpty()) itemObj.addProperty("to_asset_name", rule.targetAccount2)
                                        }
                                    }
                                }
                            } else {
                                matchedRules.forEach { rule ->
                                    rule.targetType?.let { json.addProperty("type", it) }
                                    if (!rule.targetCategory.isNullOrEmpty()) json.addProperty("category_name", rule.targetCategory)
                                    if (!rule.targetAccount1.isNullOrEmpty()) json.addProperty("asset_name", rule.targetAccount1)
                                    if (!rule.targetAccount2.isNullOrEmpty()) json.addProperty("to_asset_name", rule.targetAccount2)
                                }
                            }
                            Logger.d(ctx, "AIService", "Overridden JSON: $json")
                            json.toString()
                        } else if (element.isJsonArray) {
                            val arr = element.asJsonArray
                            arr.forEach { item ->
                                if (item.isJsonObject) {
                                    val itemObj = item.asJsonObject
                                    matchedRules.forEach { rule ->
                                        rule.targetType?.let { itemObj.addProperty("type", it) }
                                        if (!rule.targetCategory.isNullOrEmpty()) itemObj.addProperty("category_name", rule.targetCategory)
                                        if (!rule.targetAccount1.isNullOrEmpty()) itemObj.addProperty("asset_name", rule.targetAccount1)
                                        if (!rule.targetAccount2.isNullOrEmpty()) itemObj.addProperty("to_asset_name", rule.targetAccount2)
                                    }
                                }
                            }
                            Logger.d(ctx, "AIService", "Overridden Array: $arr")
                            val wrapper = JsonObject()
                            wrapper.add("bills", arr)
                            wrapper.toString()
                        } else {
                            content
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                        content
                    }
                } else {
                    content
                }
            } else {
                content
            }

            val result = if (isMultiMode) {
                // 如果是多账单，则可能返回空但无异常 (null) 或无法解析 (Exception)
                val cleaned = cleanJsonString(finalContent)
                val json = try {
                    JSONObject(cleaned)
                } catch (e: Exception) {
                    // 如果不是 JSON，尝试找是否存在 JSON 代码块并手动提取
                    throw IllegalArgumentException("AI响应非JSON: ${cleaned.take(50)}...")
                }
                
                if (!json.has("bills") && json.has("amount")) {
                   val wrapper = JSONObject()
                   wrapper.put("bills", JSONArray().put(json))
                   wrapper
                } else if (json.has("bills")) {
                   json
                } else {
                   // 既不是 bills 列表也不是 amount 单条，说明响应有问题
                   throw IllegalArgumentException("多账单模式下 AI 返回的数据缺少关键字段 'bills' 或 'amount'")
                }
            } else {
                val cleaned = cleanJsonString(finalContent)
                val json = try {
                    JSONObject(cleaned)
                } catch (e: Exception) {
                   throw IllegalArgumentException("AI响应非JSON: ${cleaned.take(50)}...")
                }

                if (json.has("bills")) {
                    val bills = json.getJSONArray("bills")
                    if (bills.length() > 0) bills.getJSONObject(0) else null
                } else {
                    json
                }
            }

            // 4. 分类合法性检查与修正
            result?.let { root ->
                enforceExpenseForReceiptSummaries(root, userInput)
                if (root.has("bills")) {
                    val billsArr = root.getJSONArray("bills")
                    for (i in 0 until billsArr.length()) {
                        val b = billsArr.getJSONObject(i)
                        val type = b.optInt("type", 0)
                        val candidates = if (type == 1) incomeCats else expenseCats
                        applyReceiptCategoryHeuristics(b, candidates)
                        val rawCate = b.optString("category_name", "")
                        val normalized = rawCate.replace(" > ", "/::/").replace(" - ", "/::/").replace(" / ", "/::/").trim()
                        
                        val matched = findBestMatch(normalized, candidates)
                        if (matched != null) {
                            b.put("category_name", matched)
                        } else if (normalized.isNotEmpty()) {
                            b.put("category_name", resolveOtherCategory(candidates))
                        }
                    }
                } else if (root.has("amount")) {
                    val type = root.optInt("type", 0)
                    val candidates = if (type == 1) incomeCats else expenseCats
                    applyReceiptCategoryHeuristics(root, candidates)
                    val rawCate = root.optString("category_name", "")
                    val normalized = rawCate.replace(" > ", "/::/").replace(" - ", "/::/").replace(" / ", "/::/").trim()

                    val matched = findBestMatch(normalized, candidates)
                    if (matched != null) {
                        root.put("category_name", matched)
                    } else if (normalized.isNotEmpty()) {
                        root.put("category_name", resolveOtherCategory(candidates))
                    }
                }
            }
            result
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Logger.d(ctx, "AIService", "AI Request Failed: ${e.message}")
            throw e
        }
    }

    /**
     * 在候选库中寻找最佳匹配
     */
    private fun findBestMatch(input: String, candidates: List<String>): String? {
        if (input.isEmpty()) return null
        if (candidates.contains(input)) return input
        candidates.find { it.endsWith("/::/$input") }?.let { return it }
        candidates.find { it.startsWith("$input/::/") }?.let { return it }
        candidates.find { input.contains(it) || it.contains(input) }?.let { return it }
        return null
    }

    private fun resolveOtherCategory(candidates: List<String>): String {
        val otherMatch = candidates.find { it.contains("其他") }
        if (otherMatch != null) return otherMatch
        return if (candidates.isNotEmpty()) candidates.first() else "其他"
    }

    private fun applyReceiptCategoryHeuristics(target: JSONObject, candidates: List<String>) {
    }

    private fun looksLikeReceiptExpenseSummary(text: String): Boolean {
        val normalized = text.lowercase()
        val expenseSignals = listOf(
            "购买", "花了", "总计花费", "小票", "receipt", "discount", "visa",
            "mastercard", "刷卡", "支付", "超市", "商店", "biedronka", "pln", "eur"
        )
        val incomeSignals = listOf(
            "工资", "收入", "收款", "收到", "到账", "退款到账", "报销到账", "转入", "打款给我"
        )
        val expenseScore = expenseSignals.count { normalized.contains(it) }
        val hasIncomeSignal = incomeSignals.any { normalized.contains(it) }
        return expenseScore >= 2 && !hasIncomeSignal
    }

    private fun enforceExpenseForReceiptSummaries(root: JSONObject, userInput: String) {
        if (!looksLikeReceiptExpenseSummary(userInput)) return

        if (root.has("bills")) {
            val billsArr = root.getJSONArray("bills")
            for (i in 0 until billsArr.length()) {
                val bill = billsArr.getJSONObject(i)
                val currentType = bill.optInt("type", 0)
                if (currentType == 0 || currentType == 1) {
                    bill.put("type", 0)
                }
            }
        } else if (root.has("amount")) {
            val currentType = root.optInt("type", 0)
            if (currentType == 0 || currentType == 1) {
                root.put("type", 0)
            }
        }
    }

    private fun cleanJsonString(input: String): String {
        var s = input.trim()
        if (s.startsWith("```json")) s = s.removePrefix("```json")
        if (s.startsWith("```")) s = s.removePrefix("```")
        if (s.endsWith("```")) s = s.removeSuffix("```")
        return s.trim()
    }

    suspend fun fetchModels(ctx: Context, apiKey: String): List<String> {
        return fetchModelsWithDetails(Prefs.getAiUrl(ctx), apiKey)
    }

    suspend fun fetchModelsWithDetails(url: String, apiKey: String): List<String> {
        var baseUrl = url
        if (baseUrl.isEmpty()) baseUrl = "https://api.siliconflow.cn/"
        if (!baseUrl.endsWith("/")) baseUrl += "/"
        val client = okhttp3.OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
        val api = Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(SiliconFlowApi::class.java)
        return try {
            val response = api.getModels("Bearer $apiKey")
            response.data.map { it.id }.sorted()
        } catch (e: Exception) {
            e.printStackTrace()
            throw e
        }
    }

    suspend fun simpleChat(ctx: Context, prompt: String): String {
        val apiKey = Prefs.getAiKey(ctx)
        val model = Prefs.getAiRuleModel(ctx)
        if (apiKey.isEmpty()) {
            throw IllegalArgumentException("请先在设置中配置 API Key")
        }
        val request = ChatRequest(
            model = model,
            messages = listOf(MessageUnion.Text(Message("user", prompt))),
            response_format = null // Not expecting JSON
        )
        val response = getApi(ctx).chatRaw("Bearer $apiKey", buildRawRequest(request))
        return response.choices.first().message.content
    }

    /**
     * 多模态：直接发送图片Base64给AI分析小票（返回自然语言摘要）
     */
    suspend fun analyzeReceiptByImage(ctx: Context, imageBase64: String, mimeType: String = "image/jpeg"): String {
        Logger.d(ctx, "AIService", "analyzeReceiptByImage: multimodal mode")
        val apiKey = Prefs.getAiKey(ctx)
        if (apiKey.isEmpty()) throw IllegalArgumentException("请先在设置中配置 API Key")

        val model = Prefs.getAiReceiptModel(ctx)
        val systemPrompt = buildReceiptSystemPrompt(ctx, "")  // 多模态模式，主要为了获得 Prompt 文本
        val dataUrl = "data:$mimeType;base64,$imageBase64"

        // 构建多模态请求体（不再强制 JSON 格式）
        val requestJson = com.google.gson.JsonObject().apply {
            addProperty("model", model)
            addProperty("temperature", 0.3)
            // 移除 response_format = json_object，让 AI 输出自然语言
            add("messages", com.google.gson.JsonArray().apply {
                // system
                add(com.google.gson.JsonObject().apply {
                    addProperty("role", "system")
                    addProperty("content", systemPrompt)
                })
                // user with image
                add(com.google.gson.JsonObject().apply {
                    addProperty("role", "user")
                    add("content", com.google.gson.JsonArray().apply {
                        add(com.google.gson.JsonObject().apply {
                            addProperty("type", "image_url")
                            add("image_url", com.google.gson.JsonObject().apply {
                                addProperty("url", dataUrl)
                            })
                        })
                        add(com.google.gson.JsonObject().apply {
                            addProperty("type", "text")
                            addProperty("text", "请分析这张小票/订单图片，转为自然语言清单。")
                        })
                    })
                })
            })
        }

        return try {
            val response = getApi(ctx).chatRaw("Bearer $apiKey", requestJson)
            val content = response.choices.first().message.content
            Logger.d(ctx, "AIService", "Receipt multimodal response: $content")
            content //直接返回文本
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Logger.d(ctx, "AIService", "analyzeReceiptByImage failed: ${e.message}")
            throw e
        }
    }

    /**
     * OCR模式：将OCR提取的文本发给AI分析小票（返回自然语言摘要）
     */
    suspend fun analyzeReceiptByOcrText(ctx: Context, ocrText: String): String {
        Logger.d(ctx, "AIService", "analyzeReceiptByOcrText, text length=${ocrText.length}")
        val apiKey = Prefs.getAiKey(ctx)
        if (apiKey.isEmpty()) throw IllegalArgumentException("请先在设置中配置 API Key")

        val model = Prefs.getAiReceiptModel(ctx)
        val systemPrompt = buildReceiptSystemPrompt(ctx, ocrText)

        return try {
            val requestJson = com.google.gson.JsonObject().apply {
                addProperty("model", model)
                addProperty("temperature", 0.3)
                // 移除 response_format = json_object
                add("messages", com.google.gson.JsonArray().apply {
                    add(com.google.gson.JsonObject().apply {
                        addProperty("role", "system")
                        addProperty("content", systemPrompt)
                    })
                    add(com.google.gson.JsonObject().apply {
                        addProperty("role", "user")
                        addProperty("content", "以下是从小票OCR识别出的文本内容，请转为自然语言清单：\n\n$ocrText")
                    })
                })
            }
            val response = getApi(ctx).chatRaw("Bearer $apiKey", requestJson)
            val content = response.choices.first().message.content
            Logger.d(ctx, "AIService", "Receipt OCR response: $content")
            content //直接返回文本
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Logger.d(ctx, "AIService", "analyzeReceiptByOcrText failed: ${e.message}")
            throw e
        }
    }

    /**
     * 检测文本是否主要为外语（非中文）
     * 通过统计非CJK字符占比判断：若拉丁字母超过一定比例，视为外语小票
     */
    private fun isForeignText(text: String): Boolean {
        if (text.isEmpty()) return false
        val letters = text.filter { it.isLetter() }
        if (letters.isEmpty()) return false
        val chineseCount = letters.count { it.code in 0x4E00..0x9FFF || it.code in 0x3400..0x4DBF }
        val latinCount = letters.count { it.code in 0x0041..0x007A || it.code in 0x00C0..0x024F }
        // 如果拉丁字母数量远多于中文字符，视为外语小票
        return latinCount > chineseCount * 2
    }

    private fun buildReceiptSystemPrompt(ctx: Context, ocrText: String = ""): String {
        val assets = Prefs.getAssets(ctx).map { it.name }
        val currencies = Prefs.getActiveCurrencies(ctx).toList()
        val expenseCats = mutableListOf<String>()
        Prefs.getCategories(ctx, Prefs.TYPE_EXPENSE).forEach { parentNode ->
            if (parentNode.subs.isEmpty()) expenseCats.add(parentNode.name)
            else parentNode.subs.forEach { childNode -> expenseCats.add("${parentNode.name}/::/${childNode.name}") }
        }
        val incomeCats = mutableListOf<String>()
        Prefs.getCategories(ctx, Prefs.TYPE_INCOME).forEach { parentNode ->
            if (parentNode.subs.isEmpty()) incomeCats.add(parentNode.name)
            else parentNode.subs.forEach { childNode -> incomeCats.add("${parentNode.name}/::/${childNode.name}") }
        }
        val now = java.util.Date()
        val timeFormat = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
        val weekFormat = java.text.SimpleDateFormat("EEEE", java.util.Locale.getDefault())
        val currentTimeStr = "${timeFormat.format(now)} (${weekFormat.format(now)})"

        // 选择提示词：优先用户自定义 → 再根据语言模式选内置模板
        val customPrompt = Prefs.getReceiptBillPrompt(ctx)
        val basePrompt = if (customPrompt.isNotEmpty()) {
            customPrompt
        } else {
            val langMode = Prefs.getReceiptLangMode(ctx)
            when (langMode) {
                Prefs.RECEIPT_LANG_CN -> RECEIPT_BILL_PROMPT_CN
                Prefs.RECEIPT_LANG_FOREIGN -> RECEIPT_BILL_PROMPT_FOREIGN
                else -> {
                    // 自动检测：根据OCR文本内容判断语言
                    if (ocrText.isNotEmpty() && isForeignText(ocrText)) {
                        Logger.d(ctx, "AIService", "Auto-detected: foreign receipt")
                        RECEIPT_BILL_PROMPT_FOREIGN
                    } else {
                        Logger.d(ctx, "AIService", "Auto-detected: Chinese receipt")
                        RECEIPT_BILL_PROMPT_CN
                    }
                }
            }
        }

        val hardenedPrompt = basePrompt + """

【补充硬规则】
1. 这是购物小票摘要任务，默认语义全部是支出，不是收入。
2. 如果商品有折扣，必须输出折后实付金额，不要输出原价。
3. 如果是称重商品，必须输出最终小计，不要输出单价。
4. 同名商品在小票中出现几次就输出几次，不要合并。
5. 只输出“购买xxx花了xx 币种”以及最后的“总计花费为xx 币种”。
"""

        return hardenedPrompt.replace("{{TIME}}", currentTimeStr)
            .replace("{{ASSETS}}", Gson().toJson(assets))
            .replace("{{EXPENSE_CATS}}", Gson().toJson(expenseCats))
            .replace("{{INCOME_CATS}}", Gson().toJson(incomeCats))
            .replace("{{CURRENCIES}}", Gson().toJson(currencies))
    }

    private fun parseReceiptResponse(content: String): org.json.JSONObject? {
        val cleaned = cleanJsonString(content)
        return try {
            val json = org.json.JSONObject(cleaned)
            if (json.has("bills")) json
            else {
                val wrapper = org.json.JSONObject()
                wrapper.put("bills", org.json.JSONArray().put(json))
                wrapper
            }
        } catch (e: Exception) {
            throw IllegalArgumentException("小票AI响应非JSON: ${cleaned.take(80)}...")
        }
    }

    /**
     * 将 ChatRequest 转为原始 JsonObject（兼容新旧两种消息格式）
     */
    private fun buildRawRequest(request: ChatRequest): com.google.gson.JsonObject {
        val gson = com.google.gson.GsonBuilder()
            .registerTypeAdapter(MessageUnion::class.java, MessageUnionSerializer())
            .create()
        return gson.fromJson(gson.toJson(request), com.google.gson.JsonObject::class.java)
    }
}
