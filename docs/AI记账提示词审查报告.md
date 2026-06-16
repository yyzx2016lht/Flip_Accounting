# AI 记账提示词审查报告

> 审查日期：2026-06-16
> 审查范围：文本记账、语音记账、图片记账（小票/截图）的全部 system prompt

---

## 目录

1. [问题一：截图记账 prompt 自相矛盾](#问题一截图记账-prompt-自相矛盾)
2. [问题二：isFromChat 导致不同入口缓存不互通](#问题二isfromchat-导致不同入口缓存不互通)
3. [问题三：分类规则重复声明浪费 token](#问题三分裂规则重复声明浪费-token)
4. [问题四：Receipt Vision 输出格式混乱导致下游解析风险](#问题四receipt-vision-输出格式混乱导致下游解析风险)
5. [问题五：杂项缺陷](#问题五杂项缺陷)
6. [附录：缓存机制说明](#附录缓存机制说明)

---

## 问题一：截图记账 prompt 自相矛盾

### 涉及文件

- [AIPrompts.kt](app/src/main/java/com/taostudio/tapaccounting/AIPrompts.kt) — `SCREEN_ACCOUNTING_PROMPT_DEFAULT`
- [AIAccountingPromptBuilder.kt](app/src/main/java/com/taostudio/tapaccounting/AIAccountingPromptBuilder.kt) — `buildScreenAccountingSystemPrompt()`

### 问题描述

`buildScreenAccountingSystemPrompt()` 以 `SCREEN_ACCOUNTING_PROMPT_DEFAULT` 为基础，在 Chat 场景下追加多段补充规则，但这些补充规则与基础 prompt 中的指令冲突。模型在同一个请求中收到矛盾指令，行为不确定。

### 矛盾清单

#### 矛盾 A：requires_review 的"必须有" vs "不用输出"

**基础 prompt 明确要求：**

> `AIPrompts.kt` 第 23 行：
> "截图/图片记账存在误识别风险，所有可记账结果都必须标记为 requires_review=true，让用户先核对，不要暗示已经最终入账。"

**基础 prompt 的输出格式也要求：**

> 第 78 行：
> `{"source_kind":"screen_capture","requires_review":true,"confidence":0.0,"natural_summary":"...","risk_flags":[],"bills":[...]}`

**Chat 场景补充：**

> `AIAccountingPromptBuilder.kt` 第 156 行：
> "【对话图片记账补充】成功时输出 bills 与 assistant_reply；无需输出 requires_review、natural_summary、risk_flags。"

**冲突后果：** Chat 场景下模型同时看到"必须输出 requires_review=true"和"无需输出 requires_review"。实测中可能表现为：有时输出有时不输出、直接忽略整条补充指令、或者输出后再犹豫修改。

#### 矛盾 B：输出格式的三套标准

Chat 场景下，`buildScreenAccountingSystemPrompt()` 追回了三组输出格式指令：

1. **基础 prompt 的格式**（第 77-84 行）：要求 `source_kind`、`requires_review`、`confidence`、`natural_summary`、`risk_flags`
2. **`buildChatUnifiedAccountingOutputRule()` 的格式**（第 147-153 行 `buildAccountingSystemPrompt` 中定义，但同样被引用至此）：要求 `bills` + `assistant_reply`，或 `no_bill` + `reply`
3. **第 156 行的补充**：说"无需输出 requires_review、natural_summary、risk_flags"

三个指令叠加后，模型需要自行判断到底该输出哪个 JSON 结构。对于弱模型（如便宜的文本模型），这几乎必然导致格式错误。

#### 矛盾 C：no_bill 判定逻辑重复

**基础 prompt 已有：**

> 第 21-22 行：
> "若无法确认画面里存在可记账内容，返回：`{"no_bill":true,"reply":"未识别到可记账内容"}`"

**Chat 场景又追加：**

> `buildChatIntentAwarenessRule()`（第 121 行调用）：
> "先判断本轮是否真的要新增或补充账单；纯闲聊、追问、寒暄返回 no_bill。"

两条规则都在说"无账单时返回 no_bill"，但判定标准不同：基础 prompt 标准是"画面中是否存在可确认的交易"，追加规则是"用户是否在闲聊"。两者叠加后，如果用户在 Chat 中上传一张包含交易的截图但口吻是闲聊的，模型就面临冲突——到底以画面内容为准，还是以对话语气为准？

### 修复方案

**思路：** 将 `SCREEN_ACCOUNTING_PROMPT_DEFAULT` 拆分为两个独立的 prompt，消除运行时拼接带来的矛盾。

**具体做法：**

1. 新增 `SCREEN_ACCOUNTING_PROMPT_CHAT` 常量，专用于 Chat 场景的截图记账。它直接从 `SCREEN_ACCOUNTING_PROMPT_DEFAULT` 派生，但**移除所有与非 Chat 场景绑定的逻辑**（`requires_review`、`natural_summary`、`risk_flags`），**内联 Chat 场景需要的逻辑**（`no_bill` + `reply` / `bills` + `assistant_reply`）。

2. 删除 `buildScreenAccountingSystemPrompt()` 中针对 `isFromChat` 的条件追加逻辑（第 120-122 行、第 147-157 行），改为：

```kotlin
internal fun buildScreenAccountingSystemPrompt(
    ctx: Context,
    promptContext: AIAccountingPromptContext,
    isFromChat: Boolean = false
): String {
    // Chat 场景和非 Chat 场景使用不同的基础 prompt，避免运行时拼接矛盾
    var prompt = if (isFromChat) {
        AIService.SCREEN_ACCOUNTING_PROMPT_CHAT  // 新常量
    } else {
        AIService.SCREEN_ACCOUNTING_PROMPT_DEFAULT
    }

    // 以下动态规则对两个场景通用，且不会与基础 prompt 冲突
    prompt += buildScreenCategoryHintRule(promptContext.expenseLeafCats, promptContext.incomeLeafCats)
    prompt += AIPrompts.buildVisualPaymentMethodRule(promptContext.assetFeatureEnabled, promptContext.assetNames)

    val creditCardNames = creditCardNames(promptContext)
    if (promptContext.assetFeatureEnabled && creditCardNames.isNotEmpty()) {
        prompt += AIPrompts.buildRepaymentRule(creditCardNames, true)
    }

    val assetCurrencyHints = assetCurrencyHints(promptContext)
    if (promptContext.assetFeatureEnabled && assetCurrencyHints.isNotEmpty()) {
        prompt += AIPrompts.buildAssetCurrencyRule(assetCurrencyHints, true)
    }

    // Chat 专属：回复风格和内联到基础 prompt 中了，不再追加
    // 非 Chat 场景：基础 prompt 已包含完整输出格式，无需追加

    return renderPromptTemplate(prompt, promptContext, ...)
}
```

3. `SCREEN_ACCOUNTING_PROMPT_CHAT` 的内容建议：

```
你是"截图/图片记账视觉助手"。
你会收到一张截图或图片。你的任务是从画面中只提取真实可记账的交易信息，输出记账账单 JSON。

【数据源】
- 资产库（含币种）: {{ASSETS}}
- 支出分类: {{EXPENSE_CATS}}
- 收入分类: {{INCOME_CATS}}
- 可用货币: {{CURRENCIES}}

【核心识别原则】
（与 DEFAULT 版相同的内容...）

【输出格式 — 对话场景】
必须只返回一个 JSON 对象：
- 成功提取交易：{"bills":[...], "assistant_reply":"简短自然回复"}
- 无交易/纯闲聊：{"no_bill":true, "reply":"..."}
- 不要输出 requires_review、natural_summary、risk_flags、source_kind 等字段。
- 不要输出 Markdown、代码块或额外文字。
```

---

## 问题二：isFromChat 导致不同入口缓存不互通

### 涉及文件

- [AIAccountingPromptBuilder.kt](app/src/main/java/com/taostudio/tapaccounting/AIAccountingPromptBuilder.kt) — `buildAccountingSystemPrompt()`、`buildScreenAccountingSystemPrompt()`
- [AIService.kt](app/src/main/java/com/taostudio/tapaccounting/AIService.kt) — `analyzeAccounting()`、`analyzeScreenAccountingByImage()`

### 问题描述

同一用户、同样的资产和分类，在以下两种场景下记账时，产生的 system prompt **不同**：

| 入口 | system prompt 差异 |
|---|---|
| 独立记账页 | base + typeRule + ... + outputJsonRuleWithTargetFields |
| Chat 对话框 | base + chatIntentAwarenessRule + ... + replyStyle + aiName + unifiedOutputRule |

这意味着：
- 用户在 Chat 里记账 → API 缓存了 Chat 版的 system prompt
- 用户切到独立记账页记账 → system prompt 不同，**缓存未命中**，需要重新处理全部 prompt tokens
- 反之亦然

### 缓存影响分析

设用户 70% 在 Chat 中记账、30% 在独立页记账：
- Chat 调用之间缓存命中（system prompt 相同）✅
- 独立页调用之间缓存命中（system prompt 相同）✅
- Chat ↔ 独立页切换时缓存未命中 ❌

虽然同入口内缓存有效，但两个入口的缓存空间是**隔离的**，这浪费了一次缓存机会。而且对于 API 提供商来说，缓存通常有 TTL（例如 DeepSeek 的缓存默认 5-10 分钟过期），如果用户在短时间内切换入口，本来可以复用的缓存被浪费了。

### 修复方案

**思路：** 让 Chat 和非 Chat 场景共享同一个 system prompt，差异通过 user message 表达。

**具体做法：**

1. 将所有 Chat 专属的指令从 system prompt 中移除，改为在 user message 中通过一个场景标记来控制。

2. 修改 `buildAccountingUserPrompt()`，增加场景标记：

```kotlin
internal fun buildAccountingUserPrompt(
    userInput: String,
    currentTimeStr: String,
    matchedPromptRules: List<DbAiRule>,
    assetFeatureEnabled: Boolean,
    isFromChat: Boolean = false,  // 新增参数
    aiName: String = ""           // 新增参数
): String = buildString {
    if (isFromChat) {
        appendLine("【场景】对话记账模式。你需要理解对话上下文中的指代（如"刚才那笔""再来一笔"），并在成功记账后输出 assistant_reply 字段作为对用户的自然语言回复。")
        appendLine("【你的名字】$aiName")
    } else {
        appendLine("【场景】独立记账模式。直接输出账单 JSON，不需要 assistant_reply。")
    }
    appendLine("【参考时间】$currentTimeStr")
    // ... 其余不变
}
```

3. 从 `buildAccountingSystemPrompt()` 中移除所有 `if (isFromChat)` 条件分支。System prompt 变为**完全静态 + 动态规则（基于旧稳定的资产/分类）**，不再依赖 `isFromChat`。

4. System prompt 中统一输出格式为：

```
【输出格式】
成功记账：{"bills":[...]}
无法记账：{"no_bill":true,"reply":"..."}
（对话模式下额外输出 assistant_reply；独立模式下不输出）
```

这样改之后：
- 同一用户的 system prompt 在任何入口下完全相同 → **缓存 100% 互通**
- Chat 特有的行为指令（理解指代、输出 assistant_reply）通过 user message 传递 → 不影响 system prompt 缓存
- `aiName` 也不再影响 system prompt → 用户改 AI 名字不会导致缓存失效

---

## 问题三：分类规则重复声明浪费 token

### 涉及文件

- [AIPrompts.kt](app/src/main/java/com/taostudio/tapaccounting/AIPrompts.kt) — 多个 `buildXxxRule()` 函数
- [AIAccountingPromptBuilder.kt](app/src/main/java/com/taostudio/tapaccounting/AIAccountingPromptBuilder.kt) — `buildAccountingSystemPrompt()`、`buildScreenCategoryHintRule()`

### 问题描述

同一个语义——"category_name 必须按交易性质选择分类，不要用商户名/平台名当分类"——在多个规则块中被重复声明：

| 位置 | 内容 |
|---|---|
| `MULTI_BILL_PROMPT_DEFAULT` 第 2 条 | "category_name 优先命中更细的子分类" |
| `MULTI_BILL_PROMPT_DEFAULT` 第 50-57 行 | 分类必须按交易性质选择（含 3 个示例） |
| `buildMultiFastModeRule()` | "每条 bill 必须独立判断分类...禁止因为同属一个父类就全部归入同一个子分类" + "超市/小票商品分类时，优先按商品本体理解" |
| `buildNoAssetAccountingRule()` | "category_name 从可选分类中选择最合适的一条" |
| `buildIncomeCategoryHardRule()` | "type=1 时 category_name 必须从收入分类中原样选择" |
| `buildScreenCategoryHintRule()` | "分类必须按交易性质选择，不要把商户名、平台名、收款方名称直接当分类" |
| `adaptPromptForCategoryDepth()` | 追加"分类层级约束"规则块 |

这些规则的核心语义可以合并为一条不超过 100 字的规则，当前分散后估计浪费 **~200-300 tokens / 请求**。

更重要的是，这些重复让 prompt 的"信息密度"下降——模型注意力被分散到多个语义相近的指令上，反而可能降低遵循度。

### 修复方案

**思路：** 将分类相关的所有规则合并为一个权威块，放在基础 prompt 中（或作为一个独立的 build 函数），在最终拼接时只出现一次。

**合并后的分类规则块（建议内容）：**

```
【分类规则（高优先）】
1. category_name 只从可用分类列表中原样选择。
2. 支出从支出分类中选，收入从收入分类中选。
3. 分类必须基于交易"性质/用途"，不是商户名/平台名。
   示例：酒店→住宿，外卖→餐饮，API服务→软件/服务
4. 优先命中子分类，格式为"一级 - 二级"。
5. 多条账单必须逐条独立判断子分类，不得因同属一个父类而合并。
6. 超市商品按商品本体分类：水果→水果类，蔬菜→蔬菜类，饼干→零食类。
7. 无法判断时选"其他/其它"，无兜底类目时才留空。
8. 收入分类禁止使用"收入""入账"等泛词。
```

约 ~150 字，覆盖了原来分散在 7 处的所有规则。

**实施步骤：**

1. 在 `AIPrompts` 中新增常量 `CATEGORY_RULES_COMPACT`，内容为上述合并块。

2. 从以下位置删除分类相关规则：
   - `MULTI_BILL_PROMPT_DEFAULT` 第 2 条和第 50-57 行
   - `MULTI_BILL_PROMPT_DEFAULT`（无账户版）第 3 条和第 8 条
   - `SCREEN_ACCOUNTING_PROMPT_DEFAULT` 第 50-57 行
   - `buildMultiFastModeRule()` 中关于分类的 3 段
   - `buildNoAssetAccountingRule()` 中关于分类的部分
   - `buildIncomeCategoryHardRule()`（整体可删除，因为合并块已包含）
   - `buildScreenCategoryHintRule()`（整体可删除，因为合并块已包含）

3. 在各 prompt builder 中**只追加一次** `CATEGORY_RULES_COMPACT`。

4. 注意 `buildMultiFastModeRule` 和 `buildNoAssetAccountingRule` 各自还包含非分类的内容（如输出格式、字段要求），这些需要保留，只移除其中的分类部分。

---

## 问题四：Receipt Vision 输出格式混乱导致下游解析风险

### 涉及文件

- [AIPrompts.kt](app/src/main/java/com/taostudio/tapaccounting/AIPrompts.kt) — `RECEIPT_VISION_RETRY_PROMPT_DEFAULT`
- [AIReceiptHelper.kt](app/src/main/java/com/taostudio/tapaccounting/AIReceiptHelper.kt) — `sanitizeReceiptSummaryText()`

### 问题描述

`RECEIPT_VISION_RETRY_PROMPT_DEFAULT` 是一个视觉模型直接分析图片后输出自然语言清单的 prompt。它覆盖三种图片类型，但每种类型的输出格式不统一：

| 图片类型 | 输出格式指令（prompt 原文） |
|---|---|
| 小票（第 216 行） | `购买中文名 (原文) 花了金额 币种` |
| 支付截图（第 220 行） | `支付/购买/消费 对象 花了金额 币种` |
| 收入截图（第 221 行） | `收到/到账/退款 对象 金额 币种` |
| 转账截图（第 222 行） | `从付款账户转给收款对象 金额 币种` |

**核心问题：** "花了"关键词只出现在前两种格式中，收入格式和转账格式**不含"花了"**。

但下游解析函数 `sanitizeReceiptSummaryText()` 使用以下逻辑提取商品名：

```kotlin
// AIReceiptHelper.kt 第 178-183 行
val spentIdx = line.indexOf("spent", ignoreCase = true)
val rawName = when {
    line.contains("花了") -> line.substringBefore("花了")
    spentIdx >= 0 -> line.substring(0, spentIdx)
    else -> line.replace(lineRegex, "").trim()  // 直接去掉金额
}
```

如果模型按收入格式输出：
```
收到工资 5000.00 CNY
```

- `line.contains("花了")` → `false`
- `spentIdx` → `-1`
- 走到 else 分支 → 用 regex 去掉金额部分 → 得到 `"收到工资"` ✓ 意外地能工作

但如果是转账格式：
```
从招商银行转给张三 5000.00 CNY
```

- else 分支去掉金额 → 得到 `"从招商银行转给张三"` — 这个字符串被当成"商品名"传给后续记账解析，但实际上是转账描述，语义完全不同。

更严重的是，如果视觉模型自由发挥，输出了 prompt 中没有明确规定格式的行（例如 `退款 商品名 金额`），解析逻辑完全不可预测。

### 修复方案

**思路：** 统一所有场景的输出格式为一个固定模板，让下游解析变得可预测。

**具体做法：**

1. 修改 `RECEIPT_VISION_RETRY_PROMPT_DEFAULT` 的输出格式部分，统一为：

```
【输出格式（硬约束）】
每行一条真实交易，格式固定为：
方向 | 商品/对象 | 金额 | 币种 | 时间(可选) | 支付方式(可选)

方向仅允许：支出、收入、转账、还款
示例：
- 支出 | 维也纳酒店 | 292.41 | CNY | 2026-06-15 14:30:00 | 招商银行
- 收入 | 工资 | 15000.00 | CNY
- 转账 | 从招商银行到工商银行 | 5000.00 | CNY
- 还款 | 招商银行信用卡 | 2000.00 | CNY

不要输出其他格式，不要输出解释或总计。
```

2. 修改 `sanitizeReceiptSummaryText()` 中的解析逻辑，直接按 `|` 分隔符解析，而不是靠 `"花了"` 这种不可靠的关键词：

```kotlin
// 新的解析逻辑
val parts = line.split("|").map { it.trim() }
if (parts.size >= 4) {
    val direction = parts[0]  // 支出/收入/转账/还款
    val item = parts[1]       // 商品/对象
    val amount = parseReceiptPrice(parts[2])
    val currency = parts[3]
    // ...
}
```

3. 同步修改 `RECEIPT_BILL_PROMPT`、`RECEIPT_BILL_PROMPT_CN`、`RECEIPT_BILL_PROMPT_FOREIGN` 三个 OCR 文本 prompt，也使用相同的 `|` 分隔格式。这样可以统一小票 OCR 和小票图片两条路径的输出格式。

---

## 问题五：杂项缺陷

### 5.1 `buildScreenModeRule` 是死代码

**位置：** [AIPrompts.kt:478-492](app/src/main/java/com/taostudio/tapaccounting/AIPrompts.kt#L478-L492)

全项目搜索无任何调用。`buildScreenAccountingSystemPrompt()` 使用的是 `SCREEN_ACCOUNTING_PROMPT_DEFAULT` + `buildScreenCategoryHintRule()`，而非 `buildScreenModeRule`。

**修复：** 直接删除。

---

### 5.2 `MULTI_BILL_PROMPT_DEFAULT` 输出示例使用 `0.0` 可能误导模型

**位置：** [AIPrompts.kt:302](app/src/main/java/com/taostudio/tapaccounting/AIPrompts.kt#L302)

```
{"bills":[{"amount":0.0,"type":0,"asset_name":"","category_name":"",...}]}
```

当模型不确定金额时（例如 OCR 模糊），可能会"学习"示例中的 `0.0`，输出一个 0 元账单而不是报 `no_bill`。

**修复：** 改为一个有意义的示例数字：

```
{"bills":[{"amount":12.34,"type":0,"asset_name":"微信","category_name":"餐饮 - 午餐","time":"2026-06-15 12:30:00","remarks":"黄焖鸡米饭","currency":"CNY","to_asset_name":"","fee":0}]}
```

使用 `12.34` 而不是 `0.0` 作为示例金额——这是一个明显是示例的数字，不会被模型误认为是"空值"。

---

### 5.3 `MULTI_BILL_PROMPT_CONCISE` 缺少关键规则

**位置：** [AIPrompts.kt:338-347](app/src/main/java/com/taostudio/tapaccounting/AIPrompts.kt#L338-L347)

这个 prompt 非常简短（10 行），没有包含任何分类规则、时间规则、币种规则、输出格式规范。如果它被用作任何实际调用的 prompt，产出将不可控。

**排查：** 确认 `MULTI_BILL_PROMPT_CONCISE` 在项目中的实际调用位置。如果确实有调用路径，需要补全规则；如果没有调用，应标记为 deprecated 或删除。

---

### 5.4 `buildIntentRouterPrompt` 用 `replace` 修改静态 prompt

**位置：** [AIPrompts.kt:113-128](app/src/main/java/com/taostudio/tapaccounting/AIPrompts.kt#L113-L128)

当 `enableQuery=false` 时，`buildIntentRouterPrompt()` 用 `replace` 修改 `INTENT_ROUTER_PROMPT_DEFAULT` 的内容。这导致两种状态下 system prompt 不同，缓存不互通。

**修复：** 保持 system prompt 不变，在 user message 中传入 `query_enabled: false` 标记：

```
【系统状态】
Query 功能: 已禁用（查询类请求请走 GENERAL_CHAT）
```

---

### 5.5 中英混杂冗余指令

**位置：** [AIPrompts.kt:472-496](app/src/main/java/com/taostudio/tapaccounting/AIPrompts.kt#L472-L496)

多处英文指令与已有的中文指令语义重复：

- `"You must return one valid JSON object only. Do not return markdown or extra explanation."` — 这个语义在中文提示词中大量出现（"严禁输出 Markdown、解释、代码块"）

**修复：** 统一为中文，移除英文重复指令。如果英文指令是为了适配某些英文模型（如 DeepSeek），保留一处即可。

---

## 附录：缓存机制说明

### 所用 API 提供商的缓存机制

| 提供商 | 缓存方式 | 缓存 TTL |
|---|---|---|
| DeepSeek | 前缀匹配（Prompt Cache），自动生效 | ~5-10 分钟 |
| 硅基流动 | 透传至底层模型，取决于具体路由的模型 | 取决于模型 |
| Qwen（通义千问） | 部分模型支持 Context Cache | 取决于模型 |
| Kimi（月之暗面） | 前缀匹配 | 取决于模型 |
| MiMo（小米） | 不明确 | — |

这些提供商的缓存机制都是**基于 messages 数组前缀的精确匹配**：
- 从 `messages[0]`（通常是 system 消息）开始计算
- 任何字符变化都会在变化点截断缓存
- 缓存命中时，prompt tokens 费用大幅降低（DeepSeek 缓存命中仅收 10% 价格）

### 当前架构下缓存命中情况

```
System prompt 结构（以文本记账为例）：
┌─────────────────────────────────────────────┐
│ [基础 prompt - 固定]                         │ ← 对同一用户固定
│ 【数据源】                                   │
│   {{ASSETS}} ← 用户不改资产时固定            │ ← 一旦稳定就不会变
│   {{EXPENSE_CATS}} ← 用户不改分类时固定      │
│   {{INCOME_CATS}} ← 用户不改分类时固定       │
│   {{CURRENCIES}} ← 用户不改货币时固定        │
│ [核心规则 - 固定]                            │
│ [buildTypeRule - 取决于 assetFeature]        │ ← 稳定后不会变
│ [buildRemarksRichnessRule - 固定]            │
│ [buildIncomeCategoryHardRule - 固定]         │
│ [buildBookFieldRule - 取决于账本列表]       │ ← 稳定后不会变
│ [buildRepaymentRule - 取决于信用卡列表]     │ ← 稳定后不会变
│ [buildAssetCurrencyRule - 取决于外币资产]   │ ← 稳定后不会变
│ [buildAccountingDateRule - 固定]             │
│ [buildReceiptSemanticRule - 固定]            │
│ [buildMultiFastModeRule - 含分类列表]        │ ← 稳定后不会变
│ [outputRule - 取决于 isFromChat]             │ ← 🔴 这一个是变量
└─────────────────────────────────────────────┘
```

**结论：** 在用户磨合期后（资产、分类、信用卡等不再变动），system prompt 对一个固定的入口（Chat 或独立页）是**完全稳定的**，缓存可以正常命中。唯一破坏缓存的因素是 `isFromChat` 导致的两个入口之间的 system prompt 差异（问题二）。

### 缓存优化优先级调整

鉴于用户的反馈（资产/分类在磨合期后很少变动），优化重心从"把所有变量移到 user message"调整为：

1. **修复 Chat/非 Chat 入口隔离**（问题二）—— 让同一用户的缓存跨入口复用
2. **修复 prompt 内容 bug**（问题一、问题四）—— 提升输出质量
3. **Token 瘦身**（问题三）—— 减少重复规则
4. **清理和统一**（问题五）—— 代码维护性

---

*本文档由 AI 辅助生成，建议逐条确认后分批实施。*
